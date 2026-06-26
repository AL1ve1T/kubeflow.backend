package com.kubevizor.ingestion;

import com.kubevizor.aggregation.GraphStateManager;
import com.kubevizor.model.NodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Processes OTLP metrics payloads from Beyla network metrics.
 * Extracts network flow data (beyla.network.flow.bytes) and registers
 * topology edges in the graph based on pod-to-pod communication.
 */
@Component
public class NetworkFlowProcessor {

    private static final Logger log = LoggerFactory.getLogger(NetworkFlowProcessor.class);

    private static final java.util.Set<String> IGNORED_WORKLOAD_NAMES = java.util.Set.of(
            "kubernetes",
            "kubernetes.default.svc",
            "kubernetes.default.svc.cluster.local");
    private static final String NETWORK_FLOW_METRIC = "beyla.network.flow.bytes";
    private static final String EXTERNAL_NODE_NAME = "external";
    // Synthetic source node for traffic originating from a different namespace
    // within the cluster.
    private static final String INTERNAL_NODE_NAME = "internal";

    private final GraphStateManager graphStateManager;

    public NetworkFlowProcessor(GraphStateManager graphStateManager) {
        this.graphStateManager = graphStateManager;
    }

    public void processMetrics(Map<String, Object> metricsPayload) {
        List<Map<String, Object>> resourceMetrics = getList(metricsPayload, "resourceMetrics");
        int flowCount = 0;

        for (Map<String, Object> rm : resourceMetrics) {
            List<Map<String, Object>> scopeMetrics = getList(rm, "scopeMetrics");
            for (Map<String, Object> sm : scopeMetrics) {
                List<Map<String, Object>> metrics = getList(sm, "metrics");
                for (Map<String, Object> metric : metrics) {
                    String name = getString(metric, "name");
                    if (NETWORK_FLOW_METRIC.equals(name)) {
                        flowCount += processNetworkFlowMetric(metric);
                    }
                }
            }
        }

        if (flowCount > 0) {
            log.info("Processed {} network flow data points into topology edges", flowCount);
        }
    }

    private int processNetworkFlowMetric(Map<String, Object> metric) {
        int count = 0;

        // Network flow bytes can be a sum, gauge, or histogram — check all
        List<Map<String, Object>> dataPoints = extractDataPoints(metric);
        for (Map<String, Object> dp : dataPoints) {
            Map<String, String> attrs = extractAttributes(getList(dp, "attributes"));

            String srcOwner = attrs.get("k8s.src.owner.name");
            String dstOwner = attrs.get("k8s.dst.owner.name");
            String srcNamespace = attrs.get("k8s.src.namespace");
            String dstNamespace = attrs.get("k8s.dst.namespace");

            // Destination must always be a known workload in a known namespace
            if (dstOwner == null || dstOwner.isEmpty()) {
                continue;
            }
            if (dstNamespace == null || dstNamespace.isEmpty()) {
                continue;
            }
            if (isIgnoredWorkloadName(srcOwner) || isIgnoredWorkloadName(dstOwner)) {
                continue;
            }

            boolean isTrulyExternal = srcOwner == null || srcOwner.isEmpty();
            boolean isCrossNamespace = !isTrulyExternal
                    && srcNamespace != null && !srcNamespace.equals(dstNamespace);

            if (isTrulyExternal) {
                // Fully external traffic: source is outside the cluster
                graphStateManager.registerNetworkFlowEdge(EXTERNAL_NODE_NAME, null, dstOwner, dstNamespace,
                        "HTTP", inferNodeType(dstOwner), NodeType.INPUT);
                log.debug("External flow edge: external -> {}", dstOwner);
                count++;
            } else if (isCrossNamespace) {
                // Cross-namespace traffic: source is a real workload but in a different
                // namespace.
                // Collapsed into a synthetic "internal" node to keep each snapshot
                // namespace-local.
                graphStateManager.registerNetworkFlowEdge(INTERNAL_NODE_NAME, null, dstOwner, dstNamespace,
                        inferProtocol(dstOwner), inferNodeType(dstOwner), NodeType.INPUT);
                log.debug("Cross-namespace flow edge: {}/{} -> {}/{}", srcNamespace, srcOwner, dstNamespace, dstOwner);
                count++;
            } else {
                // Internal traffic: both source and destination in the same namespace
                // Skip self-referencing flows
                if (srcOwner != null && srcOwner.equals(dstOwner)) {
                    continue;
                }
                graphStateManager.registerNetworkFlowEdge(srcOwner, srcNamespace, dstOwner, dstNamespace,
                        inferProtocol(dstOwner), inferNodeType(dstOwner), NodeType.SERVICE);
                log.debug("Network flow edge: {} -> {}", srcOwner, dstOwner);
                count++;
            }
        }
        return count;
    }

    /**
     * Infers node type from Kubernetes workload name using naming conventions.
     * Used as initial heuristic when no OTel trace has provided authoritative
     * type info. GraphStateManager will upgrade the type when a trace arrives.
     */
    NodeType inferNodeType(String ownerName) {
        if (ownerName == null)
            return NodeType.SERVICE;
        String lower = ownerName.toLowerCase();
        if (containsAny(lower, "postgres", "mysql", "mariadb", "mongo", "cassandra", "elasticsearch", "cockroach"))
            return NodeType.DATABASE;
        if (containsAny(lower, "redis", "memcached", "hazelcast", "dragonfly"))
            return NodeType.CACHE;
        if (containsAny(lower, "kafka", "rabbitmq", "nats", "activemq", "pulsar"))
            return NodeType.QUEUE;
        return NodeType.SERVICE;
    }

    /**
     * Infers protocol from Kubernetes workload name. Falls back to HTTP.
     */
    String inferProtocol(String ownerName) {
        if (ownerName == null)
            return "TCP";
        String lower = ownerName.toLowerCase();
        if (containsAny(lower, "postgres"))
            return "postgresql";
        if (containsAny(lower, "mysql", "mariadb"))
            return "mysql";
        if (containsAny(lower, "mongo"))
            return "mongodb";
        if (containsAny(lower, "redis", "dragonfly"))
            return "redis";
        if (containsAny(lower, "kafka"))
            return "kafka";
        if (containsAny(lower, "rabbitmq"))
            return "amqp";
        if (containsAny(lower, "cassandra"))
            return "cassandra";
        if (containsAny(lower, "elasticsearch"))
            return "elasticsearch";
        return "HTTP";
    }

    private boolean containsAny(String value, String... keywords) {
        for (String kw : keywords) {
            if (value.contains(kw))
                return true;
        }
        return false;
    }

    private boolean isIgnoredWorkloadName(String ownerName) {
        return ownerName != null && IGNORED_WORKLOAD_NAMES.contains(ownerName.toLowerCase());
    }

    private List<Map<String, Object>> extractDataPoints(Map<String, Object> metric) {
        // OTLP metrics can have sum, gauge, or histogram containers
        Map<String, Object> sum = getMap(metric, "sum");
        if (sum != null) {
            return getList(sum, "dataPoints");
        }
        Map<String, Object> gauge = getMap(metric, "gauge");
        if (gauge != null) {
            return getList(gauge, "dataPoints");
        }
        Map<String, Object> histogram = getMap(metric, "histogram");
        if (histogram != null) {
            return getList(histogram, "dataPoints");
        }
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
                String strVal = extractStringValue(valueMap);
                if (strVal != null) {
                    result.put(key, strVal);
                }
            }
        }
        return result;
    }

    private String extractStringValue(Map<?, ?> valueMap) {
        Object sv = valueMap.get("stringValue");
        if (sv != null)
            return sv.toString();
        Object iv = valueMap.get("intValue");
        if (iv != null)
            return iv.toString();
        Object dv = valueMap.get("doubleValue");
        if (dv != null)
            return dv.toString();
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?>) {
            return (List<Map<String, Object>>) val;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map<?, ?>) {
            return (Map<String, Object>) val;
        }
        return null;
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
