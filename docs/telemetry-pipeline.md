# Telemetry pipeline

This document describes how raw OpenTelemetry data becomes live graph state.
The pipeline stages are **ingestion → parsing → normalization → topology
resolution → aggregation → publish**, with **cleanup** running independently.

## 1. Ingestion

Two OTLP/HTTP endpoints under `/v1` (see [api.md](api.md)):

### Trace ingestion — `OtlpTraceIngestionController`

- Accepts `ExportTraceServiceRequest` as OTLP/HTTP JSON (gzip-decoded if needed).
- Delegates to `IngestionPipeline.process(payload)`.
- After processing, calls `GraphUpdatePublisher.notifyIfChanged()`.

### Metrics ingestion — `OtlpMetricsIngestionController`

Routes a single OTLP metrics payload to two processors:

- `NetworkFlowProcessor` — Beyla network-flow topology edges.
- `ResourceMetricsProcessor` — kubeletstats CPU/memory utilization.

## 2. Parsing — `SpanParser`

Flattens the nested OTLP structure `resourceSpans → scopeSpans → spans` into
`ParsedSpan` records. It extracts:

- `service.name`, `service.namespace`, and all resource attributes.
- `traceId`, `spanId`, `parentSpanId`, span name, **span kind**.
- start/end times (unix nanos) → duration.
- status code (handles both numeric codes and string enums like
  `STATUS_CODE_ERROR`).
- span attributes (string/int/bool values flattened to strings).

Parsing is defensive: a malformed span is logged and skipped, never failing the batch.

## 3. Normalization — `SpanNormalizer`

Converts a `ParsedSpan` into an `InteractionEvent`, resolving **edge direction**:

- **Client spans** → resolve the **target** via `TopologyResolver`; edge goes
  `source service → target`.
- **Server spans** → resolve the **caller**; edge goes `caller → receiving service`.
  - Caller resolution order: `peer.service` → `client.service.name` →
    `client.address` (resolved through `PodIpResolver` if it looks like an IP,
    else used directly as a workload name).
  - Kubernetes liveness/readiness probes (`/actuator`, `/health`, `/readyz`,
    `/livez`) are skipped via `isInfraProbe`.
  - When no caller is found, only **HTTP/gRPC** server spans fall back to a
    synthetic `external` source. Non-HTTP server spans (e.g. raw Postgres/TCP)
    are dropped because their caller is unknowable and they are already covered by
    the caller's client span.

### Protocol resolution

`resolveProtocol` checks, in order: `db.system` / `db.system.name` → `rpc.system`
→ `http.method` / `http.request.method` (→ `HTTP`) → `unknown`.

> **OTel semconv note:** always check **both** `db.system` (legacy) and
> `db.system.name` (newer semconv). Beyla and newer SDKs use the `.name` form.

## 4. Topology resolution — `TopologyResolver`

For client spans, resolves the target node in priority order:

1. **Queue/messaging** — `messaging.system` (+ `messaging.destination.name`) → `QUEUE`.
2. **Database/cache** — `db.system` / `db.system.name`; `redis`, `memcached`,
   `hazelcast`, `dragonfly` map to `CACHE`, otherwise `DATABASE`. Target name
   from `db.name` / `db.namespace`, falling back to the system name.
3. **Service** — `peer.service` → `server.address` / `net.peer.name` /
   `http.host` (port stripped) → host extracted from `http.url` / `url.full`.
4. **Fallback** — the span name as an `INPUT` target.

The Kubernetes API service (`kubernetes`, `kubernetes.default.svc`,
`kubernetes.default.svc.cluster.local`) is always ignored.

### PodIpResolver

A learned `podIP → serviceName` map, populated from `k8s.pod.ip` resource
attributes on incoming spans and refreshed by `KubernetesPodWatcher` (which lists
pods from the Kubernetes API in-cluster, or via a `kubectl get pods` subprocess
locally). Used to resolve callers from `client.address` on server spans.

## 5. Beyla network flows — `NetworkFlowProcessor`

Processes the `beyla.network.flow.bytes` metric. For each data point it reads
`k8s.src.owner.name`, `k8s.dst.owner.name`, `k8s.src.namespace`,
`k8s.dst.namespace`, and classifies the edge:

- **Destination guard:** destination owner and namespace must be present.
- **Ignored:** the Kubernetes API service names are dropped.
- **Truly external** (no source owner) → `external → dst` (`INPUT` source).
- **Cross-namespace** (source owner in a different namespace) → collapsed to a
  synthetic `internal → dst` source to keep snapshots namespace-local.
- **Same-namespace** → `src → dst`, skipping self-referencing flows.

Node type and protocol are inferred from the destination workload name
(`inferNodeType`, `inferProtocol`) as an initial heuristic; a later trace
upgrades the type to its authoritative value.

> When adding a new monitored microservice, review the inference keyword lists
> and any port/namespace filters in `NetworkFlowProcessor`.

## 6. Resource metrics — `ResourceMetricsProcessor`

Reads kubeletstats metrics and updates per-pod / per-workload utilization:

- **CPU** ratio metrics: `container.cpu.utilization`, `k8s.pod.cpu.utilization`,
  `k8s.container.cpu_limit_utilization`, `k8s.pod.cpu_limit_utilization`.
- **Memory ratio** metrics (preferred when present): `container.memory.utilization`,
  `k8s.container.memory_limit_utilization`, `k8s.pod.memory_limit_utilization`.
- **Memory bytes** metrics (always available): `container.memory.working_set`,
  `k8s.pod.memory.working_set` → converted to a `[0,1]` ratio by dividing by
  `kubeflow.memory-limit-bytes` (Minikube does not emit limit-utilization metrics).

These values feed the `loadLevel` classification at snapshot-build time.

## 7. Aggregation — `GraphStateManager`

The heart of the in-memory state (see [domain-model.md](domain-model.md)):

- `registerNode` / `registerEdge` create skeleton nodes/edges and set `dirty`.
- `recordTraffic` records latency/error into the edge's 60s window.
- `registerNetworkFlowEdge` creates topology-only edges (touch, no traffic).
- `upgradeNodeTypeIfMoreSpecific` promotes node types when better info arrives.
- `updateNodeCpu/MemoryUtilization` and `updateNodePodStatus` enrich nodes.
- `buildSnapshots()` groups edges by target-node namespace and emits one
  `GraphSnapshot` per namespace, rounding metrics and zeroing stale resource values.

## 8. Pod health — `PodStatusScraper`

Scrapes the Kubernetes pod-list API on a fixed cadence
(`pod-status-scrape-interval-seconds`) and enriches nodes with pod phase and
restart counts. Two modes:

- **In-cluster** (detected via `KUBERNETES_SERVICE_HOST`): uses the
  ServiceAccount bearer token against `https://kubernetes.default.svc`.
- **Local dev**: connects to the configured `kubeflow.k8s-api-url` (e.g.
  `kubectl proxy` on `http://localhost:8001`).

Failures degrade gracefully (logged once at WARN, then suppressed).

## 9. Cleanup — `StaleGraphCleaner`

Runs every `kubeflow.cleanup-interval-seconds`. Using a cutoff of
`now - stale-threshold-seconds`:

- prunes stale pod replicas (`pruneStalePods`),
- removes edges whose staleness anchor (`lastTrafficAt` if ever trafficked, else
  `lastSeenAt`) is before the cutoff,
- removes nodes whose `lastSeenAt` is before the cutoff,
- triggers an SSE update if anything changed.

> A frequent cause of an unexpectedly empty graph in low-traffic environments is
> stale cleanup outpacing incoming telemetry — see [development.md](development.md).
