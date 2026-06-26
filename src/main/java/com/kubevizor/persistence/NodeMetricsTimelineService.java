package com.kubevizor.persistence;

import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.RequestRatePointDto;
import com.kubevizor.model.ResourceMetricsPointDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Derives per-node time-series from the persisted graph-snapshot history.
 * Each method scans snapshots in the requested window and projects the
 * relevant fields for a single node.
 */
@Service
public class NodeMetricsTimelineService {

    private final SnapshotPersistenceService snapshotPersistenceService;

    public NodeMetricsTimelineService(SnapshotPersistenceService snapshotPersistenceService) {
        this.snapshotPersistenceService = snapshotPersistenceService;
    }

    /**
     * Returns CPU and memory utilization samples for {@code nodeId} within
     * [from, to], one point per persisted snapshot that contains the node.
     * A workload owns no resources directly, so each point is the peak across
     * its pod replicas (the hottest pod) at that moment.
     */
    public List<ResourceMetricsPointDto> getResourceMetrics(String nodeId, String namespace, Instant from, Instant to) {
        return snapshotPersistenceService.getHistory(from, to, namespace).stream()
                .map(snapshot -> {
                    GraphSnapshot.NodeDto node = findNode(nodeId, snapshot);
                    if (node == null || node.pods() == null || node.pods().isEmpty()) {
                        return null;
                    }
                    double cpu = node.pods().stream()
                            .mapToDouble(GraphSnapshot.PodDto::cpuUtilization).max().orElse(0.0);
                    double mem = node.pods().stream()
                            .mapToDouble(GraphSnapshot.PodDto::memoryUtilization).max().orElse(0.0);
                    return new ResourceMetricsPointDto(snapshot.generatedAt(), cpu, mem);
                })
                .filter(p -> p != null)
                .toList();
    }

    /**
     * Returns inbound request-rate samples for {@code nodeId} within [from, to].
     * The value at each point is the sum of {@code requestsPerSecond} across all
     * edges whose {@code targetNodeId} equals {@code nodeId} in that snapshot.
     */
    public List<RequestRatePointDto> getRequestRate(String nodeId, String namespace, Instant from, Instant to) {
        return snapshotPersistenceService.getHistory(from, to, namespace).stream()
                .map(snapshot -> {
                    double rps = snapshot.edges().stream()
                            .filter(e -> nodeId.equals(e.targetNodeId()))
                            .mapToDouble(GraphSnapshot.EdgeDto::requestsPerSecond)
                            .sum();
                    return new RequestRatePointDto(snapshot.generatedAt(), rps);
                })
                .toList();
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
