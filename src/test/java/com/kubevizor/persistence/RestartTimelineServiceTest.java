package com.kubevizor.persistence;

import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.NodeType;
import com.kubevizor.model.PodPhase;
import com.kubevizor.model.RestartEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestartTimelineServiceTest {

        private RestartTimelineService service;

        @BeforeEach
        void setUp() {
                // RestartTimelineService only uses extractEvents() in tests — no DB needed.
                service = new RestartTimelineService(null);
        }

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        private static GraphSnapshot snapshot(Instant generatedAt, String nodeId, int restartCount,
                        Instant lastRestartAt, String reason) {
                GraphSnapshot.NodeDto node = new GraphSnapshot.NodeDto(
                                nodeId, nodeId, NodeType.SERVICE,
                                PodPhase.RUNNING, restartCount, 0,
                                lastRestartAt, reason,
                                generatedAt, List.of());
                return new GraphSnapshot("default", List.of(node), List.of(), generatedAt);
        }

        private static GraphSnapshot snapshotWithPhase(Instant generatedAt, String nodeId, int restartCount,
                        Instant lastRestartAt, String reason, PodPhase phase) {
                GraphSnapshot.NodeDto node = new GraphSnapshot.NodeDto(
                                nodeId, nodeId, NodeType.SERVICE,
                                phase, restartCount, 0,
                                lastRestartAt, reason,
                                generatedAt, List.of());
                return new GraphSnapshot("default", List.of(node), List.of(), generatedAt);
        }

        private static GraphSnapshot snapshotWithoutNode(Instant generatedAt) {
                return new GraphSnapshot("default", List.of(), List.of(), generatedAt);
        }

        // -------------------------------------------------------------------------
        // Tests
        // -------------------------------------------------------------------------

        @Test
        void extractEvents_returnsEmpty_whenNoSnapshots() {
                List<RestartEventDto> events = service.extractEvents("order-service", List.of());
                assertTrue(events.isEmpty());
        }

        @Test
        void extractEvents_returnsEmpty_whenNodeNeverRestarts() {
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                snapshot(Instant.parse("2026-05-20T10:01:00Z"), "order-service", 0, null, null),
                                snapshot(Instant.parse("2026-05-20T10:02:00Z"), "order-service", 0, null, null));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);
                assertTrue(events.isEmpty());
        }

        @Test
        void extractEvents_detectsRestartOnCountIncrease() {
                Instant restartTs = Instant.parse("2026-05-20T10:01:30Z");
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                snapshot(Instant.parse("2026-05-20T10:01:00Z"), "order-service", 0, null, null),
                                snapshot(Instant.parse("2026-05-20T10:02:00Z"), "order-service", 1, restartTs,
                                                "OOMKilled"));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(1, events.size());
                RestartEventDto e = events.get(0);
                assertEquals(Instant.parse("2026-05-20T10:02:00Z"), e.detectedAt());
                assertEquals(restartTs, e.restartAt());
                assertEquals("OOMKilled", e.reason());
                assertEquals(1, e.restartCount());
                assertEquals(1, e.countDelta());
        }

        @Test
        void extractEvents_deduplicatesSameLastRestartAt() {
                Instant restartTs = Instant.parse("2026-05-20T10:01:30Z");
                // Same restartAt value appears in three consecutive snapshots.
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                snapshot(Instant.parse("2026-05-20T10:02:00Z"), "order-service", 1, restartTs, "Error"),
                                snapshot(Instant.parse("2026-05-20T10:03:00Z"), "order-service", 1, restartTs, "Error"),
                                snapshot(Instant.parse("2026-05-20T10:04:00Z"), "order-service", 1, restartTs,
                                                "Error"));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(1, events.size(), "same lastRestartAt across snapshots must not produce duplicate events");
        }

        @Test
        void extractEvents_detectsMultipleDistinctRestarts() {
                Instant firstRestart = Instant.parse("2026-05-20T10:01:30Z");
                Instant secondRestart = Instant.parse("2026-05-20T10:05:00Z");
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                snapshot(Instant.parse("2026-05-20T10:02:00Z"), "order-service", 1, firstRestart,
                                                "Error"),
                                snapshot(Instant.parse("2026-05-20T10:03:00Z"), "order-service", 1, firstRestart,
                                                "Error"),
                                snapshot(Instant.parse("2026-05-20T10:06:00Z"), "order-service", 2, secondRestart,
                                                "OOMKilled"));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(2, events.size());
                assertEquals(1, events.get(0).countDelta());
                assertEquals("Error", events.get(0).reason());
                assertEquals(1, events.get(1).countDelta());
                assertEquals("OOMKilled", events.get(1).reason());
        }

        @Test
        void extractEvents_reportsDeltaForCrashStorm() {
                // Workload crashed twice between scrape intervals: count jumps by 2.
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                snapshot(Instant.parse("2026-05-20T10:15:00Z"), "order-service", 2,
                                                Instant.parse("2026-05-20T10:14:00Z"), "Error"));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(1, events.size());
                assertEquals(2, events.get(0).countDelta());
                assertEquals(2, events.get(0).restartCount());
        }

        @Test
        void extractEvents_ignoresSnapshotsWhereNodeIsMissing() {
                Instant restartTs = Instant.parse("2026-05-20T10:03:30Z");
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                snapshotWithoutNode(Instant.parse("2026-05-20T10:01:00Z")), // node absent
                                snapshot(Instant.parse("2026-05-20T10:04:00Z"), "order-service", 1, restartTs,
                                                "Error"));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(1, events.size());
                assertEquals(restartTs, events.get(0).restartAt());
        }

        @Test
        void extractEvents_returnsEmpty_whenRestartCountDrops_dueToNewPod() {
                // If the pod is deleted and recreated, restartCount resets to 0. We should
                // not emit negative delta events — the count drop is not a restart.
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 5, null, null),
                                snapshot(Instant.parse("2026-05-20T10:05:00Z"), "order-service", 0, null, null));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);
                assertTrue(events.isEmpty(), "a restart count drop (pod replaced) must not emit an event");
        }

        // -------------------------------------------------------------------------
        // Recovery (recoveredAt) tests
        // -------------------------------------------------------------------------

        @Test
        void extractEvents_setsRecoveredAt_whenNodeBecomesRunningAfterRestart() {
                Instant restartTs = Instant.parse("2026-05-20T10:01:30Z");
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                // restart detected; pod is still CRASH_LOOP at this moment
                                snapshotWithPhase(Instant.parse("2026-05-20T10:02:00Z"), "order-service", 1, restartTs,
                                                "OOMKilled",
                                                PodPhase.CRASH_LOOP),
                                // pod is still initializing
                                snapshotWithPhase(Instant.parse("2026-05-20T10:02:30Z"), "order-service", 1, restartTs,
                                                "OOMKilled",
                                                PodPhase.PENDING),
                                // pod is healthy again
                                snapshotWithPhase(Instant.parse("2026-05-20T10:02:45Z"), "order-service", 1, restartTs,
                                                "OOMKilled",
                                                PodPhase.RUNNING));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(1, events.size());
                assertNotNull(events.get(0).recoveredAt());
                assertEquals(Instant.parse("2026-05-20T10:02:45Z"), events.get(0).recoveredAt());
        }

        @Test
        void extractEvents_recoveredAtNull_whenNodeStillDownAtEndOfWindow() {
                Instant restartTs = Instant.parse("2026-05-20T10:01:30Z");
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                snapshotWithPhase(Instant.parse("2026-05-20T10:02:00Z"), "order-service", 1, restartTs,
                                                "OOMKilled",
                                                PodPhase.CRASH_LOOP),
                                snapshotWithPhase(Instant.parse("2026-05-20T10:03:00Z"), "order-service", 1, restartTs,
                                                "OOMKilled",
                                                PodPhase.CRASH_LOOP));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(1, events.size());
                assertNull(events.get(0).recoveredAt(), "node never returned to RUNNING within the window");
        }

        @Test
        void extractEvents_setsRecoveredAtImmediately_whenDetectionSnapshotIsAlreadyRunning() {
                // Pod crashed and recovered before next scrape — the snapshot that detects
                // the restart already shows RUNNING.
                Instant restartTs = Instant.parse("2026-05-20T10:01:30Z");
                Instant detectionTs = Instant.parse("2026-05-20T10:02:00Z");
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                snapshot(detectionTs, "order-service", 1, restartTs, "Error")); // RUNNING in snapshot
                                                                                                // helper

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(1, events.size());
                assertEquals(detectionTs, events.get(0).recoveredAt(),
                                "pod was already RUNNING at detection time — recoveredAt should equal detectedAt");
        }

        @Test
        void extractEvents_secondRestartLeavesPreviousRecoveredAtNull_whenNoRecoveryBetweenCrashes() {
                Instant firstRestart = Instant.parse("2026-05-20T10:01:30Z");
                Instant secondRestart = Instant.parse("2026-05-20T10:02:30Z");
                List<GraphSnapshot> snapshots = List.of(
                                snapshot(Instant.parse("2026-05-20T10:00:00Z"), "order-service", 0, null, null),
                                // first crash, still in CRASH_LOOP
                                snapshotWithPhase(Instant.parse("2026-05-20T10:02:00Z"), "order-service", 1,
                                                firstRestart, "Error",
                                                PodPhase.CRASH_LOOP),
                                // second crash before recovery (PENDING phase, new restart count)
                                snapshotWithPhase(Instant.parse("2026-05-20T10:03:00Z"), "order-service", 2,
                                                secondRestart, "OOMKilled",
                                                PodPhase.PENDING),
                                // finally RUNNING again after second crash
                                snapshotWithPhase(Instant.parse("2026-05-20T10:03:30Z"), "order-service", 2,
                                                secondRestart, "OOMKilled",
                                                PodPhase.RUNNING));

                List<RestartEventDto> events = service.extractEvents("order-service", snapshots);

                assertEquals(2, events.size());
                assertNull(events.get(0).recoveredAt(), "first crash never saw RUNNING before second crash");
                assertEquals(Instant.parse("2026-05-20T10:03:30Z"), events.get(1).recoveredAt());
        }
}
