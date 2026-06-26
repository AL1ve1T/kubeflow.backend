package com.kubevizor.persistence;

import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.NamespaceRequestTimelinePointDto;
import com.kubevizor.model.NodeType;
import com.kubevizor.model.PodPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NamespaceRequestTimelineServiceTest {

    @Mock
    private SnapshotPersistenceService snapshotPersistenceService;

    private NamespaceRequestTimelineService service;

    private static final Instant T1 = Instant.parse("2026-05-26T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-26T10:01:00Z");
    private static final Instant T3 = Instant.parse("2026-05-26T10:02:00Z");

    @BeforeEach
    void setUp() {
        service = new NamespaceRequestTimelineService(snapshotPersistenceService);
    }

    private static GraphSnapshot.EdgeDto edge(String src, String tgt, double rps) {
        return new GraphSnapshot.EdgeDto(
                src + "->" + tgt, src, tgt, "HTTP",
                rps, 10.0, 50.0, 0L, 0.0, null, Instant.now());
    }

    private static GraphSnapshot.PodDto pod(String name, PodPhase phase) {
        return new GraphSnapshot.PodDto(
                name, 0.0, 0.0, phase, 0, null, null, Instant.now());
    }

    private static GraphSnapshot.NodeDto node(String id, List<GraphSnapshot.PodDto> pods) {
        return new GraphSnapshot.NodeDto(id, id, NodeType.SERVICE,
                PodPhase.UNKNOWN, 0, pods.size(), null, null, Instant.now(), pods);
    }

    private static GraphSnapshot snapshot(Instant generatedAt, List<GraphSnapshot.EdgeDto> edges) {
        return new GraphSnapshot(
                "default",
                List.of(new GraphSnapshot.NodeDto("order-service", "order-service", NodeType.SERVICE,
                        PodPhase.UNKNOWN, 0, 0, null, null, Instant.now(), List.of())),
                edges,
                generatedAt);
    }

    private static GraphSnapshot snapshot(Instant generatedAt, List<GraphSnapshot.NodeDto> nodes,
            List<GraphSnapshot.EdgeDto> edges) {
        return new GraphSnapshot("default", nodes, edges, generatedAt);
    }

    @Test
    void getTimeline_returnsEmptyWhenNoHistory() {
        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of());

        List<NamespaceRequestTimelinePointDto> result = service.getTimeline("default", T1, T3);

        assertTrue(result.isEmpty());
    }

    @Test
    void getTimeline_sumsAllEdgeRpsPerSnapshot() {
        GraphSnapshot snap = snapshot(T1, List.of(
                edge("auth-service", "order-service", 5.0),
                edge("order-service", "ticket-service", 3.0),
                edge("ticket-service", "postgres", 2.0)));

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of(snap));

        List<NamespaceRequestTimelinePointDto> result = service.getTimeline("default", T1, T3);

        assertEquals(1, result.size());
        assertEquals(T1, result.get(0).timestamp());
        assertEquals(10.0, result.get(0).totalRequests(), 1e-9);
    }

    @Test
    void getTimeline_returnsOnePointPerSnapshot() {
        when(snapshotPersistenceService.getHistory(any(), any(), eq("default")))
                .thenReturn(List.of(
                        snapshot(T1, List.of(edge("a", "b", 4.0))),
                        snapshot(T2, List.of(edge("a", "b", 6.0))),
                        snapshot(T3, List.of(edge("a", "b", 8.0)))));

        List<NamespaceRequestTimelinePointDto> result = service.getTimeline("default", T1, T3);

        assertEquals(3, result.size());
        assertEquals(4.0, result.get(0).totalRequests(), 1e-9);
        assertEquals(6.0, result.get(1).totalRequests(), 1e-9);
        assertEquals(8.0, result.get(2).totalRequests(), 1e-9);
    }

    @Test
    void getTimeline_healthyNamespaceHasZeroNotReadyPods() {
        GraphSnapshot snap = snapshot(T1,
                List.of(
                        node("order-service",
                                List.of(pod("order-1", PodPhase.RUNNING), pod("order-2", PodPhase.RUNNING))),
                        node("ticket-service", List.of(pod("ticket-1", PodPhase.RUNNING)))),
                List.of(edge("order-service", "ticket-service", 3.0)));

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of(snap));

        List<NamespaceRequestTimelinePointDto> result = service.getTimeline("default", T1, T3);

        assertEquals(1, result.size());
        assertEquals(3, result.get(0).totalPods());
        assertEquals(0, result.get(0).notReadyPods());
    }

    @Test
    void getTimeline_countsNonRunningPodsAsNotReady() {
        GraphSnapshot snap = snapshot(T1,
                List.of(
                        node("order-service", List.of(
                                pod("order-1", PodPhase.RUNNING),
                                pod("order-2", PodPhase.CRASH_LOOP),
                                pod("order-3", PodPhase.PENDING))),
                        node("ticket-service", List.of(
                                pod("ticket-1", PodPhase.NOT_READY)))),
                List.of(edge("order-service", "ticket-service", 3.0)));

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of(snap));

        List<NamespaceRequestTimelinePointDto> result = service.getTimeline("default", T1, T3);

        assertEquals(4, result.get(0).totalPods());
        assertEquals(3, result.get(0).notReadyPods());
        assertTrue(result.get(0).notReadyPods() <= result.get(0).totalPods());
    }

    @Test
    void getTimeline_crashLoopVisibleOnlyWithinAffectedRange() {
        GraphSnapshot healthy = snapshot(T1,
                List.of(node("order-service", List.of(pod("order-1", PodPhase.RUNNING)))),
                List.of(edge("a", "b", 1.0)));
        GraphSnapshot degraded = snapshot(T2,
                List.of(node("order-service", List.of(
                        pod("order-1", PodPhase.CRASH_LOOP),
                        pod("order-2", PodPhase.CRASH_LOOP)))),
                List.of(edge("a", "b", 1.0)));
        GraphSnapshot recovered = snapshot(T3,
                List.of(node("order-service", List.of(pod("order-1", PodPhase.RUNNING)))),
                List.of(edge("a", "b", 1.0)));

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default")))
                .thenReturn(List.of(healthy, degraded, recovered));

        List<NamespaceRequestTimelinePointDto> result = service.getTimeline("default", T1, T3);

        assertEquals(0, result.get(0).notReadyPods());
        assertTrue(result.get(1).notReadyPods() >= 2);
        assertEquals(0, result.get(2).notReadyPods());
    }

    @Test
    void getTimeline_historicalPointWithoutPodsReturnsZeros() {
        // snapshot(..) helper builds a node with an empty pods list (pre-feature
        // shape).
        GraphSnapshot snap = snapshot(T1, List.of(edge("a", "b", 5.0)));

        when(snapshotPersistenceService.getHistory(any(), any(), eq("default"))).thenReturn(List.of(snap));

        List<NamespaceRequestTimelinePointDto> result = service.getTimeline("default", T1, T3);

        assertEquals(0, result.get(0).totalPods());
        assertEquals(0, result.get(0).notReadyPods());
    }
}