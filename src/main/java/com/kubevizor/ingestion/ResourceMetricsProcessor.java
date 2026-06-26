package com.kubevizor.ingestion;

import com.kubevizor.aggregation.GraphStateManager;
import com.kubevizor.support.KubevizorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Processes OTLP metrics payloads containing Kubernetes pod resource
 * utilization.
 * Extracts container.cpu.utilization and container.memory.utilization (emitted
 * by
 * the OpenTelemetry kubeletstats receiver) and updates the corresponding Node
 * in the
 * graph. The load level on incoming edges is then derived from these values at
 * snapshot build time.
 */
@Component
public class ResourceMetricsProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResourceMetricsProcessor.class);

    // Log all unique metric names once on startup to aid debugging.
    private final java.util.concurrent.atomic.AtomicBoolean namesDumped = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    // All known kubeletstats CPU utilization metric names across receiver versions.
    // container.cpu.utilization — older kubeletstats, container scope
    // k8s.pod.cpu.utilization — pod scope (enabled by default in most versions)
    // k8s.container.cpu_limit_utilization — newer kubeletstats (requires CPU limit
    // set)
    private static final java.util.Set<String> CPU_METRIC_NAMES = java.util.Set.of(
            "container.cpu.utilization",
            "k8s.pod.cpu.utilization",
            "k8s.container.cpu_limit_utilization",
            "k8s.pod.cpu_limit_utilization");

    // Utilization ratio metrics (0.0–1.0) — emitted when the kubelet exposes
    // container limits.
    // Preferred when available; Minikube typically does NOT emit these.
    private static final java.util.Set<String> MEMORY_RATIO_METRIC_NAMES = java.util.Set.of(
            "container.memory.utilization",
            "k8s.container.memory_limit_utilization",
            "k8s.pod.memory_limit_utilization");

    // Absolute bytes metrics — always emitted by kubeletstats regardless of limit
    // availability.
    // The backend converts these to a [0,1] ratio using the configured
    // memoryLimitBytes.
    private static final java.util.Set<String> MEMORY_BYTES_METRIC_NAMES = java.util.Set.of(
            "container.memory.working_set",
            "k8s.pod.memory.working_set");

    private final GraphStateManager graphStateManager;
    private final KubevizorProperties properties;

    public ResourceMetricsProcessor(GraphStateManager graphStateManager, KubevizorProperties properties) {
        this.graphStateManager = graphStateManager;
        this.properties = properties;
    }

    public void processMetrics(Map<String, Object> metricsPayload) {
        List<Map<String, Object>> resourceMetrics = getList(metricsPayload, "resourceMetrics");
        int updated = 0;
        int totalMetricsSeen = 0;

        for (Map<String, Object> rm : resourceMetrics) {
            Map<String, Object> resource = getMap(rm, "resource");
            if (resource == null)
                continue;

            Map<String, String> resourceAttrs = extractAttributes(getList(resource, "attributes"));
            String workloadName = resolveWorkloadName(resourceAttrs);
            String namespace = resourceAttrs.get("k8s.namespace.name");
            String podName = resolvePodName(resourceAttrs, workloadName);

            if (workloadName == null || workloadName.isBlank())
                continue;

            List<Map<String, Object>> scopeMetrics = getList(rm, "scopeMetrics");
            for (Map<String, Object> sm : scopeMetrics) {
                for (Map<String, Object> metric : getList(sm, "metrics")) {
                    String name = getString(metric, "name");
                    totalMetricsSeen++;
                    if (name != null && CPU_METRIC_NAMES.contains(name)) {
                        double value = extractLatestValue(metric);
                        if (value >= 0) {
                            graphStateManager.updateNodeCpuUtilization(workloadName, namespace, podName, value);
                            log.debug("CPU utilization update [{}]: {}/{} = {}", name, workloadName, podName, value);
                            updated++;
                        }
                    } else if (name != null && MEMORY_RATIO_METRIC_NAMES.contains(name)) {
                        double value = extractLatestValue(metric);
                        if (value >= 0) {
                            graphStateManager.updateNodeMemoryUtilization(workloadName, namespace, podName, value);
                            log.debug("Memory ratio update [{}]: {}/{} = {}", name, workloadName, podName, value);
                            updated++;
                        }
                    } else if (name != null && MEMORY_BYTES_METRIC_NAMES.contains(name)) {
                        double bytes = extractLatestValue(metric);
                        if (bytes >= 0) {
                            double ratio = bytes / properties.getMemoryLimitBytes();
                            graphStateManager.updateNodeMemoryUtilization(workloadName, namespace, podName, ratio);
                            log.debug("Memory bytes update [{}]: {}/{} = {} bytes -> ratio {}", name, workloadName,
                                    podName, bytes, ratio);
                            updated++;
                        }
                    }
                }
            }
        }

        if (updated > 0) {
            log.info("Updated resource utilization for {} node metric(s)", updated);
        } else if (totalMetricsSeen > 0) {
            // Help debug misconfigured kubeletstats receivers — log the names that arrived
            java.util.Set<String> arrivedNames = new java.util.LinkedHashSet<>();
            for (Map<String, Object> rm : resourceMetrics) {
                List<Map<String, Object>> scopeMetrics = getList(rm, "scopeMetrics");
                for (Map<String, Object> sm : scopeMetrics) {
                    for (Map<String, Object> metric : getList(sm, "metrics")) {
                        String n = getString(metric, "name");
                        if (n != null)
                            arrivedNames.add(n);
                    }
                }
            }
            log.warn("Received {} metrics in resource payload but none matched utilization metric names. "
                    + "Arrived names: {}. Expected one of CPU={} or memory ratio={} or memory bytes={}",
                    totalMetricsSeen, arrivedNames, CPU_METRIC_NAMES, MEMORY_RATIO_METRIC_NAMES,
                    MEMORY_BYTES_METRIC_NAMES);
        }

        // One-time startup dump of all arriving metric names for diagnostics.
        if (totalMetricsSeen > 0 && namesDumped.compareAndSet(false, true)) {
            java.util.Set<String> allNames = new java.util.LinkedHashSet<>();
            for (Map<String, Object> rm : resourceMetrics) {
                List<Map<String, Object>> scopeMetrics = getList(rm, "scopeMetrics");
                for (Map<String, Object> sm : scopeMetrics) {
                    for (Map<String, Object> metric : getList(sm, "metrics")) {
                        String n = getString(metric, "name");
                        if (n != null)
                            allNames.add(n);
                    }
                }
            }
            log.info("[DIAG] All metric names in first kubeletstats payload: {}", allNames);
        }
    }

    /**
     * Resolves the workload (deployment) name from kubeletstats resource
     * attributes.
     * Prefers k8s.deployment.name; falls back to stripping random suffixes from
     * replicaset
     * or pod names so the result matches the node IDs used in the topology graph.
     */
    private String resolveWorkloadName(Map<String, String> attrs) {
        String deployment = attrs.get("k8s.deployment.name");
        if (deployment != null && !deployment.isBlank())
            return deployment;

        String replicaSet = attrs.get("k8s.replicaset.name");
        if (replicaSet != null && !replicaSet.isBlank())
            return stripLastSuffix(replicaSet);

        String pod = attrs.get("k8s.pod.name");
        if (pod != null && !pod.isBlank())
            return stripLastTwoSuffixes(pod);

        return null;
    }

    /**
     * Resolves the pod identity for a kubeletstats resource block. Prefers the
     * actual pod name so each replica is tracked separately; falls back to the
     * workload name when no pod name is present (a single synthetic replica).
     */
    private String resolvePodName(Map<String, String> attrs, String workloadName) {
        String pod = attrs.get("k8s.pod.name");
        if (pod != null && !pod.isBlank())
            return pod;
        return workloadName;
    }

    /**
     * Strips the two random hash suffixes appended by the Deployment controller
     * to Pod names.
     * Example: auth-service-6d9f8b5c7-xk9p2 → auth-service
     */
    private String stripLastTwoSuffixes(String name) {
        String[] parts = name.split("-");
        if (parts.length > 2) {
            return String.join("-", Arrays.copyOfRange(parts, 0, parts.length - 2));
        } else if (parts.length > 1) {
            return String.join("-", Arrays.copyOfRange(parts, 0, parts.length - 1));
        }
        return name;
    }

    /**
     * Strips the single hash suffix appended by the Deployment controller to
     * ReplicaSet names.
     * Example: auth-service-6d9f8b5c7 → auth-service
     */
    private String stripLastSuffix(String name) {
        String[] parts = name.split("-");
        if (parts.length > 1) {
            return String.join("-", Arrays.copyOfRange(parts, 0, parts.length - 1));
        }
        return name;
    }

    /**
     * Extracts the most recent (last) numeric value from a gauge or sum metric's
     * data points.
     * Returns -1 if no valid data point is found.
     */
    private double extractLatestValue(Map<String, Object> metric) {
        List<Map<String, Object>> dataPoints = extractDataPoints(metric);
        if (dataPoints.isEmpty())
            return -1;
        // Use the last data point as the most recent sample
        Map<String, Object> dp = dataPoints.get(dataPoints.size() - 1);
        Object asDouble = dp.get("asDouble");
        if (asDouble != null) {
            try {
                return Double.parseDouble(asDouble.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        Object asInt = dp.get("asInt");
        if (asInt != null) {
            try {
                return Double.parseDouble(asInt.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private List<Map<String, Object>> extractDataPoints(Map<String, Object> metric) {
        Map<String, Object> gauge = getMap(metric, "gauge");
        if (gauge != null)
            return getList(gauge, "dataPoints");
        Map<String, Object> sum = getMap(metric, "sum");
        if (sum != null)
            return getList(sum, "dataPoints");
        return List.of();
    }

    private Map<String, String> extractAttributes(List<Map<String, Object>> attrList) {
        Map<String, String> result = new java.util.HashMap<>();
        for (Map<String, Object> attr : attrList) {
            String key = getString(attr, "key");
            if (key == null)
                continue;
            Object value = attr.get("value");
            if (value instanceof Map<?, ?> valueMap) {
                Object sv = valueMap.get("stringValue");
                if (sv != null)
                    result.put(key, sv.toString());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof List<?> ? (List<Map<String, Object>>) val : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof Map<?, ?> ? (Map<String, Object>) val : null;
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
