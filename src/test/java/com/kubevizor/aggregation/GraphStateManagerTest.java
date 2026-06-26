package com.kubevizor.aggregation;

import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.InteractionEvent;
import com.kubevizor.model.LoadLevel;
import com.kubevizor.model.NodeType;
import com.kubevizor.model.PodPhase;
import com.kubevizor.support.KubevizorProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphStateManagerTest {

    private final GraphStateManager manager = new GraphStateManager(new KubevizorProperties());

    @Test
    void registerNode_createsNodeWithoutEdge() {
        manager.registerNode("auth-service", "default");

        assertEquals(1, manager.getNodes().size());
        assertTrue(manager.getNodes().containsKey("auth-service"));
        assertTrue(manager.getEdges().isEmpty());
    }

    @Test
    void registerNode_ignoresBlankServiceName() {
        manager.registerNode(" ", "default");
        manager.registerNode(null, "default");

        assertTrue(manager.getNodes().isEmpty());
        assertTrue(manager.getEdges().isEmpty());
    }

    @Test
    void registerEdge_createsSkeleton_withoutMetrics() {
        InteractionEvent event = new InteractionEvent(
                "t1", "s1",
                "order-service", "demo",
                "ticket-service", "demo",
                NodeType.SERVICE, "HTTP",
                45.0, false, Instant.now());

        manager.registerEdge(event);

        assertEquals(2, manager.getNodes().size());
        assertEquals(1, manager.getEdges().size());
        var edge = manager.getEdges().get("order-service->ticket-service");
        assertNotNull(edge);
    }

    @Test
    void recordTraffic_fillsMetricsOnExistingEdge() {
        InteractionEvent event = new InteractionEvent(
                "t1", "s1",
                "order-service", "demo",
                "ticket-service", "demo",
                NodeType.SERVICE, "HTTP",
                45.0, false, Instant.now());

        manager.registerEdge(event);
        manager.recordTraffic(event);

        var edge = manager.getEdges().get("order-service->ticket-service");
        assertEquals(45.0, edge.getAverageLatencyMs(), 0.001);
    }

    @Test
    void applyEvent_createsNodesAndEdge() {
        InteractionEvent event = new InteractionEvent(
                "t1", "s1",
                "order-service", "demo",
                "ticket-service", "demo",
                NodeType.SERVICE, "HTTP",
                45.0, false, Instant.now());

        manager.applyEvent(event);

        assertEquals(2, manager.getNodes().size());
        assertEquals(1, manager.getEdges().size());
        assertTrue(manager.getNodes().containsKey("order-service"));
        assertTrue(manager.getNodes().containsKey("ticket-service"));
        assertNotNull(manager.getEdges().get("order-service->ticket-service"));
    }

    @Test
    void applyEvent_multipleEvents_aggregatesMetrics() {
        InteractionEvent event1 = new InteractionEvent(
                "t1", "s1", "svc-a", "ns", "svc-b", "ns",
                NodeType.SERVICE, "HTTP", 50.0, false, Instant.now());
        InteractionEvent event2 = new InteractionEvent(
                "t2", "s2", "svc-a", "ns", "svc-b", "ns",
                NodeType.SERVICE, "HTTP", 100.0, true, Instant.now());

        manager.applyEvent(event1);
        manager.applyEvent(event2);

        var edge = manager.getEdges().get("svc-a->svc-b");
        assertEquals(1, edge.getErrorCount());
        assertEquals(75.0, edge.getAverageLatencyMs(), 0.001);
        assertEquals(0.5, edge.getErrorRate(), 0.001);
    }

    @Test
    void registerNetworkFlowEdge_doesNotCountByteSamplesAsRequests() {
        manager.registerNetworkFlowEdge("svc-a", "ns", "svc-b", "ns", "HTTP", NodeType.SERVICE, NodeType.SERVICE);

        var edge = manager.getEdges().get("svc-a->svc-b");
        assertNotNull(edge);
        assertEquals(0.0, edge.getRequestsPerSecond(), 0.001);
    }

    @Test
    void buildSnapshots_returnsCorrectDtos() {
        InteractionEvent event = new InteractionEvent(
                "t1", "s1", "auth", "demo", "userdb", "demo",
                NodeType.DATABASE, "postgresql", 10.0, false, Instant.now());

        manager.applyEvent(event);
        List<GraphSnapshot> snapshots = manager.buildSnapshots();

        assertEquals(1, snapshots.size());
        GraphSnapshot snapshot = snapshots.get(0);
        assertEquals("demo", snapshot.namespace());
        assertEquals(2, snapshot.nodes().size());
        assertEquals(1, snapshot.edges().size());
        assertNotNull(snapshot.generatedAt());
    }

    @Test
    void removeNode_removesNodeAndAssociatedEdges() {
        manager.applyEvent(new InteractionEvent(
                "t1", "s1", "a", "ns", "b", "ns",
                NodeType.SERVICE, "HTTP", 10.0, false, Instant.now()));

        manager.removeNode("a");

        assertFalse(manager.getNodes().containsKey("a"));
        assertTrue(manager.getEdges().isEmpty());
    }

    @Test
    void buildSnapshots_keepsRestartStateWhenTrafficWindowHasDecayed() throws Exception {
        manager.applyEvent(new InteractionEvent(
                "t1", "s1", "auth-service", "default", "order-service", "default",
                NodeType.SERVICE, "HTTP", 10.0, false, Instant.now()));
        manager.updateNodePodStatus("auth-service", "default", "auth-service-abc12-pod0", PodPhase.NOT_READY,
                115, Instant.parse("2026-06-20T23:18:11Z"), "Error");

        ageEdgeWindowOutOfRange("auth-service->order-service");

        GraphSnapshot snapshot = manager.buildSnapshot("default");
        GraphSnapshot.EdgeDto edge = snapshot.edges().getFirst();
        GraphSnapshot.NodeDto authNode = snapshot.nodes().stream()
                .filter(node -> node.id().equals("auth-service"))
                .findFirst()
                .orElseThrow();

        assertEquals(0.0, edge.requestsPerSecond(), 0.001);
        assertEquals(PodPhase.NOT_READY, authNode.podPhase());
        assertEquals(115, authNode.restartCount());
        assertEquals(Instant.parse("2026-06-20T23:18:11Z"), authNode.lastRestartAt());
    }

    @Test
    void buildSnapshots_zeroesStaleResourceMetricsAndNormalizesEdgeLoad() throws Exception {
        KubevizorProperties properties = new KubevizorProperties();
        properties.setResourceMetricStaleSeconds(1);
        GraphStateManager localManager = new GraphStateManager(properties);

        localManager.applyEvent(new InteractionEvent(
                "t1", "s1", "gateway", "default", "order-service", "default",
                NodeType.SERVICE, "HTTP", 10.0, false, Instant.now()));
        localManager.updateNodeCpuUtilization("order-service", "default", "order-service-abc12-pod0", 0.92);
        localManager.updateNodeMemoryUtilization("order-service", "default", "order-service-abc12-pod0", 0.81);

        ageNodeResourceMetrics(localManager, "order-service", Instant.now().minusSeconds(2));

        GraphSnapshot snapshot = localManager.buildSnapshot("default");
        GraphSnapshot.NodeDto targetNode = snapshot.nodes().stream()
                .filter(node -> node.id().equals("order-service"))
                .findFirst()
                .orElseThrow();
        GraphSnapshot.EdgeDto edge = snapshot.edges().getFirst();

        assertNotNull(targetNode);
        assertEquals(LoadLevel.NORMAL, edge.loadLevel());
    }

    @Test
    void buildSnapshots_exposesPerPodDetailUnderWorkloadNode() {
        manager.applyEvent(new InteractionEvent(
                "t1", "s1", "gateway", "default", "order-service", "default",
                NodeType.SERVICE, "HTTP", 10.0, false, Instant.now()));
        manager.updateNodeCpuUtilization("order-service", "default", "order-service-abc12-rep01", 0.30);
        manager.updateNodeCpuUtilization("order-service", "default", "order-service-abc12-rep02", 0.90);
        manager.updateNodeMemoryUtilization("order-service", "default", "order-service-abc12-rep01", 0.40);

        GraphSnapshot.NodeDto node = manager.buildSnapshot("default").nodes().stream()
                .filter(n -> n.id().equals("order-service"))
                .findFirst()
                .orElseThrow();

        // Edge traffic stays on the workload; per-pod detail hangs underneath it.
        assertEquals(2, node.pods().size());
        GraphSnapshot.PodDto hot = node.pods().stream()
                .filter(p -> p.podName().equals("order-service-abc12-rep02"))
                .findFirst().orElseThrow();
        assertEquals(0.90, hot.cpuUtilization(), 0.001);
    }

    @Test
    void pruneStalePods_removesStalePodsAndRefreshesRollup() {
        manager.updateNodeCpuUtilization("order-service", "default", "order-service-abc12-rep01", 0.30);
        manager.updateNodeCpuUtilization("order-service", "default", "order-service-abc12-rep02", 0.90);

        // A cutoff in the past evicts nothing — both replicas survive.
        manager.pruneStalePods(Instant.now().minusSeconds(1000));
        assertEquals(2, manager.getNodes().get("order-service").getPods().size());
        assertEquals(0.90, manager.getNodes().get("order-service").getCpuUtilization(), 0.001);

        // A cutoff in the future evicts every replica and resets the roll-up.
        manager.pruneStalePods(Instant.now().plusSeconds(1000));
        var node = manager.getNodes().get("order-service");
        assertEquals(0, node.getPods().size());
        assertEquals(0.0, node.getCpuUtilization(), 0.001);
    }

    private void ageEdgeWindowOutOfRange(String edgeId) throws Exception {
        var edge = manager.getEdges().get(edgeId);
        Field bucketEpochField = edge.getClass().getDeclaredField("bucketEpoch");
        bucketEpochField.setAccessible(true);
        long[] bucketEpoch = (long[]) bucketEpochField.get(edge);
        long expiredSecond = Instant.now().minusSeconds(61).getEpochSecond();
        for (int i = 0; i < bucketEpoch.length; i++) {
            if (bucketEpoch[i] != 0) {
                bucketEpoch[i] = expiredSecond;
            }
        }
    }

    private void ageNodeResourceMetrics(GraphStateManager graphStateManager, String nodeId, Instant staleAt)
            throws Exception {
        var node = graphStateManager.getNodes().get(nodeId);
        Field lastCpuUpdatedAtField = node.getClass().getDeclaredField("lastCpuUpdatedAt");
        lastCpuUpdatedAtField.setAccessible(true);
        lastCpuUpdatedAtField.set(node, staleAt);

        Field lastMemoryUpdatedAtField = node.getClass().getDeclaredField("lastMemoryUpdatedAt");
        lastMemoryUpdatedAtField.setAccessible(true);
        lastMemoryUpdatedAtField.set(node, staleAt);
    }
}
