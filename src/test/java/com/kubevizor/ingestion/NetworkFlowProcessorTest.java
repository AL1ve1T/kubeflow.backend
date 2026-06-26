package com.kubevizor.ingestion;

import com.kubevizor.aggregation.GraphStateManager;
import com.kubevizor.model.NodeType;
import com.kubevizor.support.KubevizorProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetworkFlowProcessorTest {

    private final GraphStateManager graphStateManager = new GraphStateManager(new KubevizorProperties());
    private final NetworkFlowProcessor processor = new NetworkFlowProcessor(graphStateManager);

    @Test
    void processMetrics_withHttpFlow_createsServiceEdge() {
        Map<String, Object> payload = buildPayload(
                "order-service", "default",
                "auth-service", "default",
                "8081");

        processor.processMetrics(payload);

        assertEquals(2, graphStateManager.getNodes().size());
        assertEquals(1, graphStateManager.getEdges().size());
        var edge = graphStateManager.getEdges().get("order-service->auth-service");
        assertNotNull(edge);
        assertEquals("HTTP", edge.getProtocol());
        assertEquals(0.0, edge.getRequestsPerSecond(), 0.001);
    }

    @Test
    void processMetrics_withPostgresFlow_createsDatabaseEdge() {
        Map<String, Object> payload = buildPayload(
                "ticket-service", "default",
                "postgres", "default",
                "5432");

        processor.processMetrics(payload);

        assertEquals(2, graphStateManager.getNodes().size());
        var edge = graphStateManager.getEdges().get("ticket-service->postgres");
        assertNotNull(edge);
        assertEquals("postgresql", edge.getProtocol());
        assertEquals(NodeType.DATABASE, graphStateManager.getNodes().get("postgres").getType());
    }

    @Test
    void processMetrics_skipsSelfReferencing() {
        Map<String, Object> payload = buildPayload(
                "order-service", "default",
                "order-service", "default",
                "8082");

        processor.processMetrics(payload);

        assertTrue(graphStateManager.getEdges().isEmpty());
    }

    @Test
    void processMetrics_systemNamespace_createsEdgeViaInternalNode() {
        Map<String, Object> payload = buildPayload(
                "order-service", "default",
                "kube-dns", "kube-system",
                "53");

        processor.processMetrics(payload);

        // Cross-namespace traffic is collapsed into the synthetic "internal" node
        assertNotNull(graphStateManager.getEdges().get("internal->kube-dns"));
    }

    @Test
    void processMetrics_kubernetesTarget_isIgnored() {
        Map<String, Object> payload = buildPayload(
                "kubevizor-backend", "kubevizor",
                "kubernetes", "default",
                "443");

        processor.processMetrics(payload);

        assertTrue(graphStateManager.getEdges().isEmpty());
        assertFalse(graphStateManager.getNodes().containsKey("kubernetes"));
    }

    @Test
    void processMetrics_missingSrcOwner_createsExternalEdge() {
        Map<String, Object> payload = buildPayloadWithAttrs(List.of(
                attr("k8s.dst.owner.name", "auth-service"),
                attr("k8s.src.namespace", "default"),
                attr("k8s.dst.namespace", "default"),
                attr("dst.port", "8081")));

        processor.processMetrics(payload);

        assertEquals(1, graphStateManager.getEdges().size());
        assertNotNull(graphStateManager.getEdges().get("external->auth-service"));
        assertEquals(NodeType.INPUT, graphStateManager.getNodes().get("external").getType());
    }

    @Test
    void processMetrics_emptySrcOwner_createsExternalEdge() {
        Map<String, Object> payload = buildPayload(
                "", "default",
                "auth-service", "default",
                "8081");

        processor.processMetrics(payload);

        assertEquals(1, graphStateManager.getEdges().size());
        assertNotNull(graphStateManager.getEdges().get("external->auth-service"));
    }

    @Test
    void processMetrics_crossNamespace_createsInternalNode() {
        Map<String, Object> payload = buildPayload(
                "ingress-nginx", "ingress",
                "order-service", "default",
                "8082");

        processor.processMetrics(payload);

        assertEquals(1, graphStateManager.getEdges().size());
        assertNotNull(graphStateManager.getEdges().get("internal->order-service"));
        // Cross-namespace source is collapsed into the synthetic "internal" node
        assertEquals(NodeType.INPUT, graphStateManager.getNodes().get("internal").getType());
        assertEquals(NodeType.SERVICE, graphStateManager.getNodes().get("order-service").getType());
    }

    @Test
    void processMetrics_externalToPostgres_createsEdge() {
        Map<String, Object> payload = buildPayloadWithAttrs(List.of(
                attr("k8s.dst.owner.name", "postgres"),
                attr("k8s.dst.namespace", "default"),
                attr("dst.port", "5432")));

        processor.processMetrics(payload);

        // External traffic to any known workload is tracked regardless of port
        assertNotNull(graphStateManager.getEdges().get("external->postgres"));
        assertEquals(NodeType.DATABASE, graphStateManager.getNodes().get("postgres").getType());
    }

    @Test
    void processMetrics_multipleFlows_createsMultipleEdges() {
        Map<String, Object> payload = buildMultiFlowPayload(
                List.of(
                        flowAttrs("order-service", "default", "auth-service", "default", "8081"),
                        flowAttrs("order-service", "default", "ticket-service", "default", "8083"),
                        flowAttrs("ticket-service", "default", "postgres", "default", "5432")));

        processor.processMetrics(payload);

        assertEquals(4, graphStateManager.getNodes().size());
        assertEquals(3, graphStateManager.getEdges().size());
        assertNotNull(graphStateManager.getEdges().get("order-service->auth-service"));
        assertNotNull(graphStateManager.getEdges().get("order-service->ticket-service"));
        assertNotNull(graphStateManager.getEdges().get("ticket-service->postgres"));
    }

    @Test
    void processMetrics_anyPort_createsEdge() {
        Map<String, Object> payload = buildPayload(
                "service-a", "default",
                "service-b", "default",
                "9090");

        processor.processMetrics(payload);

        // Port is irrelevant — all intra-cluster traffic to non-system namespaces is
        // tracked
        assertEquals(1, graphStateManager.getEdges().size());
        assertNotNull(graphStateManager.getEdges().get("service-a->service-b"));
    }

    @Test
    void processMetrics_nonDefaultNamespace_createsEdge() {
        Map<String, Object> payload = buildPayload(
                "api-gateway", "staging",
                "payment-service", "staging",
                "8081");

        processor.processMetrics(payload);

        assertEquals(2, graphStateManager.getNodes().size());
        assertEquals(1, graphStateManager.getEdges().size());
        assertNotNull(graphStateManager.getEdges().get("api-gateway->payment-service"));
        assertEquals("staging", graphStateManager.getNodes().get("api-gateway").getNamespace());
        assertEquals("staging", graphStateManager.getNodes().get("payment-service").getNamespace());
    }

    @Test
    void processMetrics_ignoresNonNetworkFlowMetrics() {
        Map<String, Object> payload = Map.of(
                "resourceMetrics", List.of(Map.of(
                        "scopeMetrics", List.of(Map.of(
                                "metrics", List.of(Map.of(
                                        "name", "http.server.duration",
                                        "sum", Map.of(
                                                "dataPoints", List.of(Map.of(
                                                        "attributes", List.of(),
                                                        "asInt", "100"))))))))));

        processor.processMetrics(payload);

        assertTrue(graphStateManager.getEdges().isEmpty());
    }

    @Test
    void processMetrics_withRedisFlow_createsCacheEdge() {
        Map<String, Object> payload = buildPayload(
                "order-service", "default",
                "redis", "default",
                "6379");

        processor.processMetrics(payload);

        assertEquals(2, graphStateManager.getNodes().size());
        var edge = graphStateManager.getEdges().get("order-service->redis");
        assertNotNull(edge);
        assertEquals("redis", edge.getProtocol());
        assertEquals(NodeType.CACHE, graphStateManager.getNodes().get("redis").getType());
    }

    @Test
    void processMetrics_withKafkaFlow_createsQueueEdge() {
        Map<String, Object> payload = buildPayload(
                "order-service", "default",
                "kafka", "default",
                "9092");

        processor.processMetrics(payload);

        assertEquals(2, graphStateManager.getNodes().size());
        var edge = graphStateManager.getEdges().get("order-service->kafka");
        assertNotNull(edge);
        assertEquals("kafka", edge.getProtocol());
        assertEquals(NodeType.QUEUE, graphStateManager.getNodes().get("kafka").getType());
    }

    @Test
    void processMetrics_withRabbitMqFlow_createsQueueEdge() {
        Map<String, Object> payload = buildPayload(
                "ticket-service", "default",
                "rabbitmq", "default",
                "5672");

        processor.processMetrics(payload);

        var edge = graphStateManager.getEdges().get("ticket-service->rabbitmq");
        assertNotNull(edge);
        assertEquals("amqp", edge.getProtocol());
        assertEquals(NodeType.QUEUE, graphStateManager.getNodes().get("rabbitmq").getType());
    }

    // --- helpers ---

    private Map<String, Object> buildPayload(String srcOwner, String srcNs,
            String dstOwner, String dstNs, String dstPort) {
        return buildPayloadWithAttrs(List.of(
                attr("k8s.src.owner.name", srcOwner),
                attr("k8s.src.namespace", srcNs),
                attr("k8s.dst.owner.name", dstOwner),
                attr("k8s.dst.namespace", dstNs),
                attr("dst.port", dstPort)));
    }

    private Map<String, Object> buildPayloadWithAttrs(List<Map<String, Object>> attrs) {
        return Map.of(
                "resourceMetrics", List.of(Map.of(
                        "scopeMetrics", List.of(Map.of(
                                "metrics", List.of(Map.of(
                                        "name", "beyla.network.flow.bytes",
                                        "sum", Map.of(
                                                "dataPoints", List.of(Map.of(
                                                        "attributes", attrs,
                                                        "asInt", "12345"))))))))));
    }

    private Map<String, Object> buildMultiFlowPayload(List<List<Map<String, Object>>> flowAttrsList) {
        List<Map<String, Object>> dataPoints = flowAttrsList.stream()
                .map(attrs -> Map.<String, Object>of("attributes", attrs, "asInt", "100"))
                .toList();

        return Map.of(
                "resourceMetrics", List.of(Map.of(
                        "scopeMetrics", List.of(Map.of(
                                "metrics", List.of(Map.of(
                                        "name", "beyla.network.flow.bytes",
                                        "sum", Map.of("dataPoints", dataPoints))))))));
    }

    private List<Map<String, Object>> flowAttrs(String srcOwner, String srcNs,
            String dstOwner, String dstNs, String dstPort) {
        return List.of(
                attr("k8s.src.owner.name", srcOwner),
                attr("k8s.src.namespace", srcNs),
                attr("k8s.dst.owner.name", dstOwner),
                attr("k8s.dst.namespace", dstNs),
                attr("dst.port", dstPort));
    }

    private Map<String, Object> attr(String key, String value) {
        return Map.of("key", key, "value", Map.of("stringValue", value));
    }
}
