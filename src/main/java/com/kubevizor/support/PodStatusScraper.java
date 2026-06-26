package com.kubevizor.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubevizor.aggregation.GraphStateManager;
import com.kubevizor.model.Node;
import com.kubevizor.model.PodPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Periodically scrapes the Kubernetes pod list API to enrich graph nodes with
 * live pod health signals: phase (Running, CrashLoopBackOff, etc.) and the
 * total container restart count.
 *
 * <p>
 * Operates in two modes:
 * <ul>
 * <li><b>In-cluster</b>: detected via the {@code KUBERNETES_SERVICE_HOST}
 * environment variable. Uses the pod's ServiceAccount bearer token and
 * connects to {@code https://kubernetes.default.svc} with TLS
 * verification disabled (Minikube CA is self-signed).</li>
 * <li><b>Local dev</b>: connects to the URL configured in
 * {@code kubevizor.k8s-api-url} (default {@code http://localhost:8001},
 * which is where {@code kubectl proxy} listens).</li>
 * </ul>
 *
 * Failures are handled gracefully — consecutive failures are logged at WARN
 * once, then suppressed to avoid log spam while the API is unreachable.
 */
@Component
public class PodStatusScraper {

    private static final Logger log = LoggerFactory.getLogger(PodStatusScraper.class);

    private static final String SA_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final String IN_CLUSTER_API = "https://kubernetes.default.svc";

    private final GraphStateManager graphStateManager;
    private final ObjectMapper objectMapper;
    // Non-null only in in-cluster mode.
    private final HttpClient httpClient;
    private final String apiBaseUrl;
    private final String bearerToken;
    private final boolean inCluster;

    // Failure tracking: log warn on first failure, then every 10 after that.
    private volatile int consecutiveFailures = 0;

    public PodStatusScraper(GraphStateManager graphStateManager,
            ObjectMapper objectMapper,
            KubevizorProperties properties) {
        this.graphStateManager = graphStateManager;
        this.objectMapper = objectMapper;

        this.inCluster = System.getenv("KUBERNETES_SERVICE_HOST") != null;
        if (inCluster) {
            this.apiBaseUrl = IN_CLUSTER_API;
            this.bearerToken = readSaToken();
            this.httpClient = buildInsecureHttpClient();
            log.info("PodStatusScraper: in-cluster mode, api={}", apiBaseUrl);
        } else {
            // Local dev: use kubectl subprocess — inherits the active kubeconfig context
            // and works with any auth method (mTLS, token, exec), no kubectl proxy needed.
            this.apiBaseUrl = null;
            this.bearerToken = null;
            this.httpClient = null;
            log.info("PodStatusScraper: local dev mode — will use 'kubectl get pods' subprocess");
        }
    }

    @Scheduled(fixedDelayString = "${kubevizor.pod-status-scrape-interval-seconds:15}000")
    public void scrape() {
        // Collect the namespaces currently tracked in the graph.
        Set<String> namespaces = graphStateManager.getNodes().values().stream()
                .map(Node::getNamespace)
                .filter(ns -> ns != null && !ns.isBlank())
                .collect(Collectors.toSet());

        if (namespaces.isEmpty()) {
            return;
        }

        try {
            for (String ns : namespaces) {
                scrapeNamespace(ns);
            }
            if (consecutiveFailures > 0) {
                log.info("PodStatusScraper: recovered after {} failure(s)", consecutiveFailures);
            }
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures == 1 || consecutiveFailures % 10 == 0) {
                log.warn("PodStatusScraper: failed to scrape pod status (attempt {}): {}",
                        consecutiveFailures, e.getMessage());
            }
        }
    }

    private void scrapeNamespace(String namespace) throws Exception {
        String json;
        if (inCluster) {
            json = scrapeViaHttp(namespace);
        } else {
            json = scrapeViaKubectl(namespace);
        }
        processPodList(namespace, json);
    }

    private String scrapeViaHttp(String namespace) throws Exception {
        String url = apiBaseUrl + "/api/v1/namespaces/" + namespace + "/pods";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "k8s API returned " + response.statusCode() + " for namespace=" + namespace);
        }
        return response.body();
    }

    private String scrapeViaKubectl(String namespace) throws Exception {
        Process process = new ProcessBuilder(
                "kubectl", "get", "pods",
                "-n", namespace,
                "-o", "json")
                .redirectErrorStream(false)
                .start();

        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());

        boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("kubectl get pods timed out for namespace=" + namespace);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "kubectl get pods failed (exit=" + process.exitValue() + "): " + stderr.strip());
        }
        return stdout;
    }

    // Package-private for testing.
    void processPodList(String namespace, String json) throws Exception {
        Map<String, Object> body = objectMapper.readValue(json, new TypeReference<>() {
        });
        List<Map<String, Object>> items = getList(body, "items");

        // Each pod is pushed individually as a replica of its workload. The
        // GraphStateManager keeps per-pod health and derives the workload-level
        // roll-up (worst-case phase, highest restart count) from its pods.
        int podCount = 0;
        for (Map<String, Object> pod : items) {
            Map<String, Object> metadata = getMap(pod, "metadata");
            String podName = getString(metadata, "name");
            if (podName == null || podName.isBlank()) {
                continue;
            }

            String workloadName = resolveWorkloadName(metadata, podName);

            Map<String, Object> podStatus = getMap(pod, "status");
            PodPhase phase = classifyPod(podStatus);
            int restarts = sumRestarts(podStatus);
            Instant lastRestartAt = extractLastRestartAt(podStatus);
            String lastRestartReason = extractLastRestartReason(podStatus);

            graphStateManager.updateNodePodStatus(
                    workloadName, namespace, podName, phase, restarts, lastRestartAt, lastRestartReason);
            podCount++;
        }

        log.debug("PodStatusScraper: scraped {} pod(s) in namespace={}", podCount, namespace);
    }

    /**
     * Resolves the workload (deployment) name from a pod.
     * Prefers the ownerReference chain (pod → ReplicaSet label). Falls back to
     * stripping the two hash suffixes from the pod name — the same strategy used
     * by {@link com.kubevizor.ingestion.ResourceMetricsProcessor}.
     */
    private String resolveWorkloadName(Map<String, Object> metadata, String podName) {
        // Check for a well-known workload label that Kubernetes controllers set.
        Map<String, Object> labels = getMap(metadata, "labels");
        String appLabel = getString(labels, "app");
        if (appLabel != null && !appLabel.isBlank()) {
            return appLabel;
        }
        // Fall back to stripping the two random hash suffixes from the pod name.
        return stripLastTwoSuffixes(podName);
    }

    /**
     * Determines the {@link PodPhase} from the pod's status block.
     * CrashLoopBackOff and OOMKilled take precedence over the ready condition.
     */
    // Package-private for testing.
    PodPhase classifyPod(Map<String, Object> status) {
        // Check every container state for CrashLoopBackOff / OOMKilled.
        for (Map<String, Object> cs : getList(status, "containerStatuses")) {
            Map<String, Object> state = getMap(cs, "state");
            String waitingReason = getString(getMap(state, "waiting"), "reason");
            if ("CrashLoopBackOff".equals(waitingReason) || "OOMKilled".equals(waitingReason)) {
                return PodPhase.CRASH_LOOP;
            }
            // A container that just finished with non-zero exit also counts as degraded.
            Map<String, Object> terminated = getMap(state, "terminated");
            String termReason = getString(terminated, "reason");
            if ("OOMKilled".equals(termReason)) {
                return PodPhase.CRASH_LOOP;
            }
        }
        // Check the Ready condition.
        for (Map<String, Object> cond : getList(status, "conditions")) {
            if ("Ready".equals(getString(cond, "type"))) {
                if ("True".equals(getString(cond, "status"))) {
                    return PodPhase.RUNNING;
                }
                // Ready=False — distinguish Pending from an already-running-but-degraded pod.
            }
        }
        // Fall back to pod phase string.
        String phase = getString(status, "phase");
        if ("Pending".equals(phase)) {
            return PodPhase.PENDING;
        }
        if ("Failed".equals(phase) || "Succeeded".equals(phase)) {
            return PodPhase.FAILED;
        }
        if ("Running".equals(phase)) {
            // Running but Ready=False → not-ready (readiness probe failing, initializing).
            return PodPhase.NOT_READY;
        }
        return PodPhase.UNKNOWN;
    }

    /** Sums restartCount across all containers in the pod. */
    // Package-private for testing.
    int sumRestarts(Map<String, Object> status) {
        int total = 0;
        for (Map<String, Object> cs : getList(status, "containerStatuses")) {
            Object rc = cs.get("restartCount");
            if (rc instanceof Number n) {
                total += n.intValue();
            }
        }
        return total;
    }

    /**
     * Returns the most-recent {@code lastState.terminated.finishedAt} timestamp
     * across all containers in the pod, or {@code null} if no container has ever
     * been terminated.
     */
    // Package-private for testing.
    Instant extractLastRestartAt(Map<String, Object> status) {
        Instant latest = null;
        for (Map<String, Object> cs : getList(status, "containerStatuses")) {
            String finishedAt = getString(getMap(getMap(cs, "lastState"), "terminated"), "finishedAt");
            if (finishedAt != null) {
                try {
                    Instant ts = Instant.parse(finishedAt);
                    if (latest == null || ts.isAfter(latest)) {
                        latest = ts;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return latest;
    }

    /**
     * Returns the termination reason (e.g. {@code OOMKilled}, {@code Error}) from
     * the most-recent {@code lastState.terminated} block across all containers,
     * or {@code null} if no container has been terminated.
     */
    // Package-private for testing.
    String extractLastRestartReason(Map<String, Object> status) {
        Instant latest = null;
        String reason = null;
        for (Map<String, Object> cs : getList(status, "containerStatuses")) {
            Map<String, Object> terminated = getMap(getMap(cs, "lastState"), "terminated");
            String finishedAt = getString(terminated, "finishedAt");
            if (finishedAt != null) {
                try {
                    Instant ts = Instant.parse(finishedAt);
                    if (latest == null || ts.isAfter(latest)) {
                        latest = ts;
                        reason = getString(terminated, "reason");
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return reason;
    }

    // -------------------------------------------------------------------------
    // Workload name helpers (mirrors ResourceMetricsProcessor)
    // -------------------------------------------------------------------------

    private String stripLastTwoSuffixes(String name) {
        String[] parts = name.split("-");
        if (parts.length > 2) {
            return String.join("-", Arrays.copyOfRange(parts, 0, parts.length - 2));
        } else if (parts.length > 1) {
            return String.join("-", Arrays.copyOfRange(parts, 0, parts.length - 1));
        }
        return name;
    }

    // -------------------------------------------------------------------------
    // HTTP / SSL helpers
    // -------------------------------------------------------------------------

    private static HttpClient buildInsecureHttpClient() {
        try {
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            } }, new SecureRandom());
            return HttpClient.newBuilder()
                    .sslContext(sslCtx)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build insecure HTTP client", e);
        }
    }

    private static String readSaToken() {
        try {
            return Files.readString(Path.of(SA_TOKEN_PATH)).strip();
        } catch (Exception e) {
            log.warn("PodStatusScraper: could not read SA token from {}: {}", SA_TOKEN_PATH, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // JSON extraction helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Collections.emptyMap();
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }
}
