# Configuration

Configuration lives in `src/main/resources/application.yml` (defaults / local dev)
and `src/main/resources/application-k8s.yml` (the `k8s` Spring profile). All custom
properties are bound to `KubeflowProperties` under the `kubeflow.*` prefix.

## Spring profiles

| Profile | Activation | Datasource |
| --- | --- | --- |
| _(default)_ | local dev | **H2** file DB in PostgreSQL compatibility mode (`./data/kubeflow`), H2 console at `/h2-console` |
| `k8s` | `SPRING_PROFILES_ACTIVE=k8s` (set in the Kubernetes Deployment) | **PostgreSQL** via `DB_*` env vars, `ddl-auto: validate` |

Flyway owns the schema in both profiles, so Hibernate `ddl-auto` is `none`
locally and `validate` in-cluster. The H2 URL uses `MODE=PostgreSQL` so the same
Flyway migration SQL runs unchanged in both environments.

## `kubeflow.*` properties

| Property | Default | Meaning |
| --- | --- | --- |
| `stale-threshold-seconds` | `120` (code) / `600` (yml) | Age after which nodes/edges are removed by cleanup. |
| `cleanup-interval-seconds` | `30` | How often `StaleGraphCleaner` runs. |
| `retention-days` | `30` | Snapshot history retention window. |
| `cpu-elevated-threshold` | `0.50` | CPU ratio for `ELEVATED` load level. |
| `cpu-high-threshold` | `0.70` | CPU ratio for `HIGH`. |
| `cpu-critical-threshold` | `0.85` | CPU ratio for `CRITICAL`. |
| `mem-elevated-threshold` | `0.60` | Memory ratio for `ELEVATED`. |
| `mem-high-threshold` | `0.75` | Memory ratio for `HIGH`. |
| `mem-critical-threshold` | `0.90` | Memory ratio for `CRITICAL`. |
| `memory-limit-bytes` | `536870912` (512 MiB) | Per-container memory limit used to convert `*.memory.working_set` bytes to a `[0,1]` ratio (Minikube does not emit limit-utilization metrics). Set this to match your workload pod memory limit. |
| `pod-status-scrape-interval-seconds` | `15` | How often `PodStatusScraper` polls the Kubernetes pod API. |
| `snapshot-persist-interval-millis` | `1000` | How often the current graph is persisted **and** pushed to SSE clients (so window decay is sampled continuously). |
| `resource-metric-stale-seconds` | `30` | How long CPU/memory samples remain valid without a fresh kubeletstats update; afterward utilization and `loadLevel` fall back to calm/zero. |

> `LoadLevel` is `CRITICAL` if CPU **or** memory crosses its critical threshold,
> then `HIGH`, then `ELEVATED`, else `NORMAL`. See [domain-model.md](domain-model.md).

## Environment variables (k8s profile)

Consumed by `application-k8s.yml`:

| Variable | Default | Purpose |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | — | Set to `k8s` to activate the PostgreSQL profile. |
| `DB_HOST` | `kubeflow-postgres` | PostgreSQL host. |
| `DB_PORT` | `5432` | PostgreSQL port. |
| `DB_NAME` | `kubeflow` | Database name. |
| `DB_USERNAME` | — | Database user (required). |
| `DB_PASSWORD` | — | Database password (required). |

`KUBERNETES_SERVICE_HOST` (injected automatically in-cluster) is used by
`PodStatusScraper` / `KubernetesPodWatcher` to choose in-cluster vs local mode.
In local mode they use `kubeflow.k8s-api-url` (e.g. `kubectl proxy`) or a
`kubectl get pods` subprocess.

## Other settings

- **Actuator**: only `health` and `info` are exposed.
- **springdoc**: API docs at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`
  (tags sorted alpha, operations by method).
- **Logging**: `com.kubeflow` at `INFO` by default.

When you add a new `kubeflow.*` property, add the field + getter/setter to
`KubeflowProperties`, document it here, and set a sensible default in
`application.yml`.
