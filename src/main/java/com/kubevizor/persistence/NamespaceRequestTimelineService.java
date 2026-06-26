package com.kubevizor.persistence;

import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.NamespaceRequestTimelinePointDto;
import com.kubevizor.model.PodPhase;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class NamespaceRequestTimelineService {

    private final SnapshotPersistenceService snapshotPersistenceService;

    public NamespaceRequestTimelineService(SnapshotPersistenceService snapshotPersistenceService) {
        this.snapshotPersistenceService = snapshotPersistenceService;
    }

    public List<NamespaceRequestTimelinePointDto> getTimeline(String namespace, Instant from, Instant to) {
        return snapshotPersistenceService.getHistory(from, to, namespace).stream()
                .map(NamespaceRequestTimelineService::toPoint)
                .toList();
    }

    /**
     * Projects a single namespace snapshot onto a timeline point. Pod-readiness
     * counts are derived from the same snapshot as {@code totalRequests}, so they
     * land on the identical timestamp grid as the request curve.
     *
     * <p>
     * Both counts come from the per-pod phase list, which guarantees the
     * invariant {@code 0 <= notReadyPods <= totalPods}. Snapshots that predate
     * per-pod tracking carry no pods and therefore report {@code 0 / 0} (never
     * null).
     */
    private static NamespaceRequestTimelinePointDto toPoint(GraphSnapshot snapshot) {
        double totalRequests = snapshot.edges().stream()
                .mapToDouble(GraphSnapshot.EdgeDto::requestsPerSecond)
                .sum();

        int totalPods = 0;
        int notReadyPods = 0;
        for (GraphSnapshot.NodeDto node : snapshot.nodes()) {
            List<GraphSnapshot.PodDto> pods = node.pods();
            if (pods == null) {
                continue;
            }
            for (GraphSnapshot.PodDto pod : pods) {
                totalPods++;
                // Anything other than RUNNING (including UNKNOWN / missing) is not ready.
                if (pod.podPhase() != PodPhase.RUNNING) {
                    notReadyPods++;
                }
            }
        }

        return new NamespaceRequestTimelinePointDto(
                snapshot.generatedAt(), totalRequests, totalPods, notReadyPods);
    }
}