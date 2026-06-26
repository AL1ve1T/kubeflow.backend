# kubevizor.backend Documentation

`kubevizor.backend` is a Kubernetes observability backend. It ingests OpenTelemetry
telemetry emitted by demo workloads, transforms raw traces and metrics into a live
service-topology graph, aggregates rolling per-edge metrics, and publishes
frontend-ready graph snapshots over REST and Server-Sent Events (SSE).

This is a **domain-specific topology-processing backend** for Kubernetes workload
visualization — not a generic observability platform. It deliberately does not
replace Jaeger, Tempo, or Prometheus, and it does not store raw spans.

## What this backend does

```
OTLP traces  ─┐
              ├─► parse ─► normalize ─► resolve topology ─► aggregate ─► publish (REST + SSE)
OTLP metrics ─┘                                              │
(Beyla flows,                                                └─► persist (24h+ history)
 kubeletstats)
```

- **Receives** OTLP/HTTP payloads: distributed traces, Beyla network-flow metrics,
  and kubeletstats resource metrics.
- **Parses** spans and metric data points into internal records.
- **Normalizes** spans into `InteractionEvent` objects (direction-resolved edges).
- **Resolves** source/destination workload relationships and node types.
- **Aggregates** rolling per-edge metrics (RPS, latency, error rate) in a
  60-second sliding window and maintains live in-memory graph state.
- **Enriches** nodes with pod health (phase, restart count) and CPU/memory load.
- **Publishes** per-namespace `GraphSnapshot` objects via REST and SSE.
- **Persists** snapshots for historical replay with a rolling retention window.
- **Cleans up** stale nodes and edges automatically.

## Documentation index

| Document | Contents |
| --- | --- |
| [architecture.md](architecture.md) | System context, package layout, data flow, threading model |
| [domain-model.md](domain-model.md) | `Node`, `Edge`, `InteractionEvent`, `GraphSnapshot`, enums, DTOs |
| [telemetry-pipeline.md](telemetry-pipeline.md) | Ingestion, parsing, normalization, topology resolution, aggregation, cleanup |
| [api.md](api.md) | REST endpoints, SSE stream, OpenAPI/Swagger |
| [configuration.md](configuration.md) | `kubevizor.*` properties, Spring profiles, environment variables |
| [deployment.md](deployment.md) | Docker, Kubernetes manifests, Minikube, Beyla, OTel Collector |
| [development.md](development.md) | Local build/run, testing, debugging telemetry routing |

## Tech stack

- **Java 21**, **Spring Boot 3.4.x**, **Maven**
- In-memory live state + **PostgreSQL** persistence (H2 for local dev)
- **Flyway** schema migrations
- **OpenTelemetry** proto + protobuf for OTLP payloads
- **springdoc-openapi** for Swagger UI

## Keeping docs current

These documents are part of the source of truth. **When you change behavior,
models, endpoints, configuration, or infrastructure, update the matching
document in this directory in the same change.** See the repository
`.github/copilot-instructions.md` and `.github/agents/backend-developer.agent.md`
for the documentation-maintenance rule.
