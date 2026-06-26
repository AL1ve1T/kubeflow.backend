package com.kubevizor.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Periodically queries the Kubernetes API for pods and registers their
 * IP-to-service-name mappings in PodIpResolver.
 *
 * <p>
 * Operates in two modes:
 * <ul>
 * <li><b>In-cluster</b>: uses the SA bearer token + K8s HTTPS API.</li>
 * <li><b>Local dev</b>: uses a {@code kubectl get pods} subprocess — inherits
 * the active kubeconfig context so it works with any auth method without
 * extra configuration.</li>
 * </ul>
 */
@Component
public class KubernetesPodWatcher {

    private static final Logger log = LoggerFactory.getLogger(KubernetesPodWatcher.class);

    private static final Path TOKEN_PATH = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final Path CA_PATH = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");
    private static final String API_BASE = "https://kubernetes.default.svc";

    private final PodIpResolver podIpResolver;
    private final ObjectMapper objectMapper;
    private final String targetNamespace;

    private HttpClient httpClient;
    private final boolean inCluster;

    public KubernetesPodWatcher(PodIpResolver podIpResolver,
            ObjectMapper objectMapper,
            @Value("${kubevizor.pod-watcher.namespace:default}") String targetNamespace) {
        this.podIpResolver = podIpResolver;
        this.objectMapper = objectMapper;
        this.targetNamespace = targetNamespace;
        this.inCluster = Files.exists(TOKEN_PATH);

        if (inCluster) {
            try {
                this.httpClient = buildHttpClient();
                log.info("KubernetesPodWatcher: in-cluster mode for namespace '{}'", targetNamespace);
            } catch (Exception e) {
                log.warn("Failed to initialize K8s HTTP client, falling back to kubectl: {}", e.getMessage());
            }
        } else {
            log.info("KubernetesPodWatcher: local dev mode — will use 'kubectl get pods' subprocess for namespace '{}'",
                    targetNamespace);
        }
    }

    @Scheduled(fixedDelayString = "${kubevizor.pod-watcher.interval-ms:15000}", initialDelay = 5000)
    public void refreshPodMappings() {
        try {
            String json = inCluster && httpClient != null ? fetchViaApi() : fetchViaKubectl();
            processPodList(json);
        } catch (Exception e) {
            log.warn("Failed to refresh pod IP mappings: {}", e.getMessage());
        }
    }

    private String fetchViaApi() throws Exception {
        String token = Files.readString(TOKEN_PATH).trim();
        String url = API_BASE + "/api/v1/namespaces/" + targetNamespace + "/pods";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("K8s API returned status " + response.statusCode());
        }
        return response.body();
    }

    private String fetchViaKubectl() throws Exception {
        Process process = new ProcessBuilder(
                "kubectl", "get", "pods",
                "-n", targetNamespace,
                "-o", "json")
                .redirectErrorStream(false)
                .start();

        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());

        boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("kubectl get pods timed out for namespace=" + targetNamespace);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "kubectl get pods failed (exit=" + process.exitValue() + "): " + stderr.strip());
        }
        return stdout;
    }

    void processPodList(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            return;
        }

        int registered = 0;
        for (JsonNode pod : items) {
            String podIp = textOrNull(pod, "/status/podIP");
            String serviceName = resolveServiceName(pod);
            if (podIp != null && serviceName != null) {
                podIpResolver.register(podIp, serviceName);
                registered++;
            }
        }
        log.debug("Refreshed pod IP mappings: {} pods in namespace={}, resolver size={}",
                registered, targetNamespace, podIpResolver.size());
    }

    private String resolveServiceName(JsonNode pod) {
        // Prefer app label (common convention)
        String appLabel = textOrNull(pod, "/metadata/labels/app");
        if (appLabel != null) {
            return appLabel;
        }

        // Fall back to owner (deployment) name via pod name prefix
        String podName = textOrNull(pod, "/metadata/name");
        if (podName != null) {
            // Strip replicaset hash suffix: "order-service-79bbf4f866-c6jvl" -> try labels
            // first
            String appK8sName = textOrNull(pod, "/metadata/labels/app.kubernetes.io~1name");
            if (appK8sName != null) {
                return appK8sName;
            }
        }

        return podName;
    }

    private String textOrNull(JsonNode node, String jsonPointer) {
        JsonNode target = node.at(jsonPointer);
        return (target != null && !target.isMissingNode() && target.isTextual()) ? target.asText() : null;
    }

    private HttpClient buildHttpClient() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate caCert;
        try (FileInputStream fis = new FileInputStream(CA_PATH.toFile())) {
            caCert = (X509Certificate) cf.generateCertificate(fis);
        }

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("k8s-ca", caCert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
    }
}
