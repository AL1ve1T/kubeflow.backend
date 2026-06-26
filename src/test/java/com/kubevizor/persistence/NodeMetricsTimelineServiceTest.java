package com.kubevizor.persistence;

import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.NodeType;
import com.kubevizor.model.PodPhase;
import com.kubevizor.model.RequestRatePointDto;
import com.kubevizor.model.ResourceMetricsPointDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeMetricsTimelineServiceTest {

    @Mock
    private SnapshotPersistenceService snapshotPersistenceService;

    private NodeMetricsTimelineService service;

    private static final Instant T1 = Instant.parse("2026-05-26T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-26T10:01:00Z");
    private static final Instant T3 = Instant.parse("2026-05-26T10:02:00Z");

    @BeforeEach
    void setUp() {
        service = new NodeMetricsTimelineService(snapshotPersistenceService);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static GraphSnapshot.NodeDto node(String id, double cpu, double memory) {
        return new GraphSnapshot.NodeDto(id, id, NodeType.SERVICE,
                PodPhase.RUNNING, 0, 1, null, null, Instant.now(),
                List.of(new GraphSnapshot.PodDto(id + "-pod0", cpu, memory,
                        PodPhase.RUNNING, 0, null, null, Instant.now())));
    }

    private static GraphSnapshot.EdgeDto edge(String src, String tgt, double rps) {
        return new GraphSnapshot.EdgeDto(
                src + "->" + tgt, src, tgt, "HTTP",
                rps, 10.0, 50.0, 0L, 0.0, null, Instant.now());
    }

    private static GraphSnapshot snapshot(Instant generatedAt, List<GraphSnapshot.NodeDto> nodes,
            List<GraphSnapshot.EdgeDto> edges) {
        return new GraphSnapshot("default", nodes, edges, generatedAt);
    }

    // -------------------------------------------------------------------------
    // Resource metrics tests
    // -------------------------------------------------------------------------

    @Test
    void getResourceMetrics_returnsEmptyWhenNoHistory() {
        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of());

        List<ResourceMetricsPointDto> result = service.getResourceMetrics("order-service", "default", T1, T3);

        assertTrue(result.isEmpty());
    }

    @Test
    void getResourceMetrics_skipsSnapshotsMissingTheNode() {
        GraphSnapshot withNode = snapshot(T1, List.of(node("order-service", 0.3, 0.5)), List.of());
        GraphSnapshot withoutNode = snapshot(T2, List.of(), List.of());
        GraphSnapshot withNodeAgain = snapshot(T3, List.of(node("order-service", 0.4, 0.6)), List.of());

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default")))
                .thenReturn(List.of(withNode, withoutNode, withNodeAgain));

        List<ResourceMetricsPointDto> result = service.getResourceMetrics("order-service", "default", T1, T3);

        assertEquals(2, result.size());
        assertEquals(T1, result.get(0).timestamp());
        assertEquals(0.3, result.get(0).cpuUtilization(), 1e-9);
        assertEquals(0.5, result.get(0).memoryUtilization(), 1e-9);
        assertEquals(T3, result.get(1).timestamp());
        assertEquals(0.4, result.get(1).cpuUtilization(), 1e-9);
    }

    @Test
    void getResourceMetrics_returnsOnePointPerSnapshotContainingNode() {
        List<GraphSnapshot> snapshots = List.of(
                snapshot(T1, List.of(node("order-service", 0.1, 0.2)), List.of()),
                snapshot(T2, List.of(node("order-service", 0.5, 0.7)), List.of()),
                snapshot(T3, List.of(node("order-service", 0.9, 0.95)), List.of()));

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(snapshots);

        List<ResourceMetricsPointDto> result = service.getResourceMetrics("order-service", "default", T1, T3);

        assertEquals(3, result.size());
        assertEquals(0.1, result.get(0).cpuUtilization(), 1e-9);
        assertEquals(0.2, result.get(0).memoryUtilization(), 1e-9);
        assertEquals(0.5, result.get(1).cpuUtilization(), 1e-9);
        assertEquals(0.9, result.get(2).cpuUtilization(), 1e-9);
        assertEquals(0.95, result.get(2).memoryUtilization(), 1e-9);
    }

    // -------------------------------------------------------------------------
    // Request rate tests
    // -------------------------------------------------------------------------

    @Test
    void getRequestRate_returnsEmptyWhenNoHistory() {
        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of());

        List<RequestRatePointDto> result = service.getRequestRate("order-service", "default", T1, T3);

        assertTrue(result.isEmpty());
    }

    @Test
    void getRequestRate_sumsAllInboundEdgeRps() {
        // Two callers send to order-service: 5.0 + 3.0 = 8.0 rps
        GraphSnapshot snap = snapshot(T1,
                List.of(node("order-service", 0.0, 0.0)),
                List.of(
                        edge("auth-service", "order-service", 5.0),
                        edge("ticket-service", "order-service", 3.0),
                        edge("order-service", "postgres", 2.0))); // outbound — must not count

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of(snap));

        List<RequestRatePointDto> result = service.getRequestRate("order-service", "default", T1, T3);

        assertEquals(1, result.size());
        assertEquals(T1, result.get(0).timestamp());
        assertEquals(8.0, result.get(0).requestsPerSecond(), 1e-9);
    }

    @Test
    void getRequestRate_returnsZeroRpsWhenNodeHasNoInboundEdges() {
        GraphSnapshot snap = snapshot(T1,
                List.of(node("order-service", 0.0, 0.0)),
                List.of(edge("order-service", "postgres", 2.0)));

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of(snap));

        List<RequestRatePointDto> result = service.getRequestRate("order-service", "default", T1, T3);

        assertEquals(1, result.size());
        assertEquals(0.0, result.get(0).requestsPerSecond(), 1e-9);
    }

    @Test
    void getRequestRate_producesOnePointPerSnapshot() {
        List<GraphSnapshot> snapshots = List.of(
                snapshot(T1, List.of(), List.of(edge("auth-service", "order-service", 4.0))),
                snapshot(T2, List.of(), List.of(edge("auth-service", "order-service", 6.0))),
                snapshot(T3, List.of(), List.of(edge("auth-service", "order-service", 8.0))));

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(snapshots);

        List<RequestRatePointDto> result = service.getRequestRate("order-service", "default", T1, T3);

        assertEquals(3, result.size());
        assertEquals(4.0, result.get(0).requestsPerSecond(), 1e-9);
        assertEquals(6.0, result.get(1).requestsPerSecond(), 1e-9);
        assertEquals(8.0, result.get(2).requestsPerSecond(), 1e-9);
    }
}
