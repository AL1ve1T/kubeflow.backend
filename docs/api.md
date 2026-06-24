# API reference

All endpoints are served on port **8080**. Interactive docs are available via
Swagger UI at **`/swagger-ui.html`** (OpenAPI JSON at `/v3/api-docs`).

## Ingestion endpoints (`/v1`)

These are OTLP/HTTP receivers — normally called by the OpenTelemetry Collector,
not by humans.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/v1/traces` | Receive OTLP/HTTP JSON trace export (`ExportTraceServiceRequest`). Gzip supported. |
| `POST` | `/v1/metrics` | Receive OTLP/HTTP JSON metrics export — Beyla network flows + kubeletstats CPU/memory. |

Trace ingestion extracts per span: source service (`service.name`), target
service/database (`db.name`, `peer.service`, `net.peer.name`, …), protocol,
latency (span duration), and error flag (`otel.status_code = ERROR` or HTTP 5xx).
See [telemetry-pipeline.md](telemetry-pipeline.md).

## Graph endpoints (`/api`)

Served by `GraphController`. All snapshot data is **namespace-scoped**.

### Live graph

| Method | Path | Query params | Returns |
| --- | --- | --- | --- |
| `GET` | `/api/graph` | `namespace` (optional) | `GraphSnapshot[]` — one per namespace (or a single namespace if filtered). |
| `GET` | `/api/graph/stream` | — | **SSE** stream of `graph-update` events; each `data` is `GraphSnapshot[]`. |
| `GET` | `/api/graph/history` | `from`, `to` (ISO-8601), `namespace` | Persisted `GraphSnapshot[]` in the time range (defaults: last 1h). |

#### SSE stream behavior (`/api/graph/stream`)

- **Connect once; the server pushes updates** — do not poll.
- The **initial event** is sent immediately on connect with the full current state.
- Subsequent `graph-update` events fire after every ingestion batch that changes
  the graph, and on a fixed cadence so clients observe time-based metric decay.
- Event format: `event: graph-update\ndata: <GraphSnapshot[] JSON>`.
- Emitter timeout is 5 minutes; clients should reconnect on close.

### Node timelines

Reconstructed from persisted snapshot history (not stored as discrete events).

| Method | Path | Query params | Returns |
| --- | --- | --- | --- |
| `GET` | `/api/nodes/{nodeId}/restarts` | `from`, `to`, `namespace` (default `default`) | `RestartEventDto[]` — restart events with `restartAt`, reason (e.g. `OOMKilled`), cumulative count, delta, and `recoveredAt`. Default range: last 30 days. |
| `GET` | `/api/nodes/{nodeId}/resource-metrics` | `from`, `to`, `namespace` | `ResourceMetricsPointDto[]` — CPU/memory samples over time. Default range: last 1h. |
| `GET` | `/api/nodes/{nodeId}/request-rate` | `from`, `to`, `namespace` | `RequestRatePointDto[]` — inbound RPS (sum over edges targeting the node). Default range: last 1h. |

### Namespace timeline

| Method | Path | Query params | Returns |
| --- | --- | --- | --- |
| `GET` | `/api/namespaces/{namespace}/request-timeline` | `from`, `to` | `NamespaceRequestTimelinePointDto[]` — total requests plus pod-readiness counts (`totalPods`, `notReadyPods`) on a shared timestamp grid. Default range: last 1h. |

## Operational endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/actuator/health` | Spring Boot health (exposed; also used as a liveness/readiness signal). |
| `GET` | `/actuator/info` | Build/info. |
| `GET` | `/h2-console` | H2 web console (local dev only; disabled under the `k8s` profile). |

## Conventions

- Times are ISO-8601 `Instant`s. Numeric metric fields are rounded to 2 decimals.
- Stale resource metrics are reported as `0.0` rather than last-known values.
- When adding or changing an endpoint, keep the springdoc `@Operation` /
  `@Schema` annotations accurate and update this document.
