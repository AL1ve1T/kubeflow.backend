package com.kubevizor.cleanup;

import com.kubevizor.aggregation.GraphStateManager;
import com.kubevizor.api.GraphUpdatePublisher;
import com.kubevizor.support.KubevizorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Periodically removes stale nodes and edges from the graph.
 */
@Component
public class StaleGraphCleaner {

    private static final Logger log = LoggerFactory.getLogger(StaleGraphCleaner.class);

    private final GraphStateManager graphStateManager;
    private final KubevizorProperties properties;
    private final GraphUpdatePublisher graphUpdatePublisher;

    public StaleGraphCleaner(GraphStateManager graphStateManager,
            KubevizorProperties properties,
            GraphUpdatePublisher graphUpdatePublisher) {
        this.graphStateManager = graphStateManager;
        this.properties = properties;
        this.graphUpdatePublisher = graphUpdatePublisher;
    }

    @Scheduled(fixedDelayString = "${kubevizor.cleanup.interval-seconds:30}000")
    public void cleanStaleElements() {
        Duration staleDuration = Duration.ofSeconds(properties.getStaleThresholdSeconds());
        Instant cutoff = Instant.now().minus(staleDuration);

        // Drop pod replicas that have stopped reporting, refreshing workload roll-ups.
        graphStateManager.pruneStalePods(cutoff);

        List<String> staleEdges = new ArrayList<>();
        for (var entry : graphStateManager.getEdges().entrySet()) {
            var edge = entry.getValue();
            // If the edge ever carried request traffic, use lastTrafficAt so that
            // Beyla network-flow touches (which refresh lastSeenAt) cannot keep a
            // traffic-less edge alive indefinitely. For topology-only edges that
            // have never had a span, fall back to lastSeenAt.
            Instant stalenessAnchor = edge.getLastTrafficAt() != null
                    ? edge.getLastTrafficAt()
                    : edge.getLastSeenAt();
            if (stalenessAnchor.isBefore(cutoff)) {
                staleEdges.add(entry.getKey());
            }
        }

        List<String> staleNodes = new ArrayList<>();
        for (var entry : graphStateManager.getNodes().entrySet()) {
            if (entry.getValue().getLastSeenAt().isBefore(cutoff)) {
                staleNodes.add(entry.getKey());
            }
        }

        staleEdges.forEach(graphStateManager::removeEdge);
        staleNodes.forEach(graphStateManager::removeNode);

        if (!staleEdges.isEmpty() || !staleNodes.isEmpty()) {
            log.info("Cleaned {} stale edges and {} stale nodes", staleEdges.size(), staleNodes.size());
            graphUpdatePublisher.notifyIfChanged();
        }
    }
}
