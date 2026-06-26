# Development

## Prerequisites

- **JDK 21**
- **Maven** (or the bundled wrapper if present)
- Optional: **Minikube** + `kubectl` for the full telemetry path

## Build and run

```bash
# Build (runs tests)
mvn clean package

# Build without tests
mvn -q -DskipTests package

# Run locally (default profile → H2 file DB at ./data/kubevizor)
mvn spring-boot:run
# or
java -jar target/kubevizor-backend-*.jar
```

The backend starts on **http://localhost:8080**. Useful local URLs:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Current graph: `http://localhost:8080/api/graph`
- SSE stream: `http://localhost:8080/api/graph/stream`
- H2 console: `http://localhost:8080/h2-console`
  (JDBC URL `jdbc:h2:file:./data/kubevizor`, user `sa`, no password)

## Testing

Unit tests are the priority for the MVP (keep fixtures small and readable).
Run the full suite:

```bash
mvn test
```

Existing test coverage (under `src/test/java/com/kubevizor`) targets the pipeline
stages that matter most:

- `parsing/SpanParserTest` — span field extraction.
- `normalization/SpanNormalizerTest` — span → `InteractionEvent`, direction resolution.
- `topology/TopologyResolverTest`, `topology/KubernetesPodWatcherTest` — target resolution, pod watching.
- `ingestion/NetworkFlowProcessorTest`, `ingestion/ResourceMetricsProcessorTest` — Beyla flows, kubeletstats metrics.
- `aggregation/GraphStateManagerTest`, `model/EdgeWindowTest` — rolling aggregation and the 60s window.
- `cleanup/StaleGraphCleanerTest` — stale node/edge removal.
- `api/GraphUpdatePublisherTest` — SSE publishing.
- `persistence/*TimelineServiceTest`, `persistence/SnapshotPersistenceServiceTest` — history/timeline reconstruction.
- `support/PodStatusScraperTest` — pod health scraping.

When you add behavior, add or update the matching unit test in the same package.

## Feeding telemetry locally

To exercise the pipeline without a cluster, POST an OTLP/HTTP JSON trace payload
to `/v1/traces` (or metrics to `/v1/metrics`). With a cluster, run the OTel
Collector and point its `KUBEVIZOR_BACKEND_ENDPOINT` at the host
(`http://host.docker.internal:8080`) — see [deployment.md](deployment.md).

For pod-status/pod-IP enrichment without an in-cluster ServiceAccount, run
`kubectl proxy` (the scrapers default to `http://localhost:8001`) or rely on the
`kubectl get pods` subprocess fallback.

## Debugging an empty graph

A graph that is empty or keeps emptying usually means one of:

1. **No telemetry arriving** — check collector logs and the
   `KUBEVIZOR_BACKEND_ENDPOINT` routing (`host.docker.internal`, **not**
   `host.minikube.internal`, in this environment).
2. **Stale cleanup outpacing traffic** — under low traffic, edges/nodes age out
   after `kubevizor.stale-threshold-seconds`. Raise it for quiet demos.
3. **Resource metrics zeroed** — utilization older than
   `resource-metric-stale-seconds` reports `0.0` and calms `loadLevel`.

Verify quickly with `curl http://localhost:8080/api/graph`.

## Conventions

- Java + Spring Boot; constructor injection; records for DTOs.
- Small, single-responsibility components; avoid deep inheritance.
- Keep OTel-specific parsing isolated from internal domain models.
- Don't add comments/docstrings/annotations to code you didn't change.
- Don't over-engineer — no abstractions or error handling for cases that can't happen.

## Documentation maintenance

When a change touches behavior, models, endpoints, configuration, or
infrastructure, update the relevant file in `docs/` in the **same** change.
The mapping is enforced by `.github/copilot-instructions.md` and the
`backend-developer` agent.
