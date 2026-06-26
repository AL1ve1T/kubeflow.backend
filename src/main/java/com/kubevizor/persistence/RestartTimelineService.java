package com.kubevizor.persistence;

import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.PodPhase;
import com.kubevizor.model.RestartEventDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Derives a per-node restart event timeline by scanning the persisted
 * graph-snapshot history.
 *
 * <p>
 * Algorithm: iterate snapshots in ascending time order; for each snapshot
 * find the matching {@link GraphSnapshot.NodeDto} and compare its
 * {@code restartCount} and {@code lastRestartAt} against the previously seen
 * values. Emit a {@link RestartEventDto} whenever the count increases. If
 * consecutive snapshots report the same {@code lastRestartAt} value, the event
 * is deduplicated — only the first occurrence is emitted.
 */
@Service
public class RestartTimelineService {

    private final SnapshotPersistenceService snapshotPersistenceService;

    public RestartTimelineService(SnapshotPersistenceService snapshotPersistenceService) {
        this.snapshotPersistenceService = snapshotPersistenceService;
    }

    /**
     * Returns restart events for {@code nodeId} within [from, to] for the given
     * namespace, ordered by detection time ascending.
     */
    public List<RestartEventDto> getRestartTimeline(String nodeId, String namespace, Instant from, Instant to) {
        List<GraphSnapshot> snapshots = snapshotPersistenceService.getHistory(from, to, namespace);
        return extractEvents(nodeId, snapshots);
    }

    // Package-private for unit testing with fabricated snapshots.
    List<RestartEventDto> extractEvents(String nodeId, List<GraphSnapshot> snapshots) {
        List<RestartEventDto> events = new ArrayList<>();

        int lastRestartCount = -1; // -1 = no prior observation
        Instant lastRestartAt = null; // last restartAt value we already emitted
        boolean awaitingRecovery = false;

        for (GraphSnapshot snapshot : snapshots) {
            GraphSnapshot.NodeDto node = findNode(nodeId, snapshot);
            if (node == null) {
                continue;
            }

            int currentCount = node.restartCount();
            Instant currentRestartAt = node.lastRestartAt();

            // Fill recoveredAt for the most recent pending restart event when the node
            // is first observed as RUNNING again.
            if (awaitingRecovery && node.podPhase() == PodPhase.RUNNING) {
                int last = events.size() - 1;
                RestartEventDto prev = events.get(last);
                events.set(last, new RestartEventDto(prev.detectedAt(), prev.restartAt(), prev.reason(),
                        prev.restartCount(), prev.countDelta(), snapshot.generatedAt()));
                awaitingRecovery = false;
            }

            if (lastRestartCount == -1) {
                // First time we see this node — establish baseline, don't emit.
                lastRestartCount = currentCount;
                lastRestartAt = currentRestartAt;
                continue;
            }

            boolean countIncreased = currentCount > lastRestartCount;
            boolean newRestartTimestamp = currentRestartAt != null
                    && !Objects.equals(currentRestartAt, lastRestartAt);

            if (countIncreased || newRestartTimestamp) {
                int delta = countIncreased ? currentCount - lastRestartCount : 1;
                // If the detection snapshot already shows RUNNING the pod recovered
                // before the next scrape interval — record recovery immediately.
                Instant recoveredAt = node.podPhase() == PodPhase.RUNNING ? snapshot.generatedAt() : null;
                events.add(new RestartEventDto(
                        snapshot.generatedAt(),
                        currentRestartAt,
                        node.lastRestartReason(),
                        currentCount,
                        delta,
                        recoveredAt));
                awaitingRecovery = (recoveredAt == null);
                lastRestartCount = currentCount;
                lastRestartAt = currentRestartAt;
            }
        }

        return events;
    }

    private static GraphSnapshot.NodeDto findNode(String nodeId, GraphSnapshot snapshot) {
        for (GraphSnapshot.NodeDto node : snapshot.nodes()) {
            if (nodeId.equals(node.id())) {
                return node;
            }
        }
        return null;
    }
}
