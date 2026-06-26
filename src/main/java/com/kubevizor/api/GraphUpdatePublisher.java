package com.kubevizor.api;

import com.kubevizor.aggregation.GraphStateManager;
import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.persistence.SnapshotPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages SSE subscriptions and publishes graph updates to connected clients.
 * Publishes only when the graph state has actually changed.
 */
@Component
public class GraphUpdatePublisher {

    private static final Logger log = LoggerFactory.getLogger(GraphUpdatePublisher.class);
    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final GraphStateManager graphStateManager;
    private final SnapshotPersistenceService snapshotPersistenceService;

    public GraphUpdatePublisher(GraphStateManager graphStateManager,
            SnapshotPersistenceService snapshotPersistenceService) {
        this.graphStateManager = graphStateManager;
        this.snapshotPersistenceService = snapshotPersistenceService;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // Send current graph state immediately so the client doesn't start with an
        // empty view
        try {
            List<GraphSnapshot> snapshots = graphStateManager.buildSnapshots();
            emitter.send(SseEmitter.event()
                    .name("graph-update")
                    .data(eventPayload(snapshots)));
        } catch (IOException e) {
            emitter.complete();
            emitters.remove(emitter);
        }

        log.info("SSE client subscribed. Total clients: {}", emitters.size());
        return emitter;
    }

    /**
     * Checks if the graph has changed since the last notification.
     * If so, builds a snapshot and broadcasts to SSE clients.
     * Call this after each ingestion batch or cleanup cycle.
     */
    public void notifyIfChanged() {
        if (!graphStateManager.resetDirty()) {
            return;
        }

        List<GraphSnapshot> snapshots = graphStateManager.buildSnapshots();
        broadcast(snapshots);

        int totalNodes = snapshots.stream().mapToInt(s -> s.nodes().size()).sum();
        int totalEdges = snapshots.stream().mapToInt(s -> s.edges().size()).sum();
        log.trace("Published graph update: {} namespaces, {} nodes, {} edges",
                snapshots.size(), totalNodes, totalEdges);
    }

    /**
     * Persists the current graph on a fixed cadence so historical replay samples
     * edge-window decay continuously, even when no new telemetry arrives.
     */
    @Scheduled(fixedRateString = "${kubevizor.snapshot-persist-interval-millis:1000}")
    public void persistCurrentSnapshots() {
        List<GraphSnapshot> snapshots = graphStateManager.buildSnapshots();
        if (snapshots.isEmpty()) {
            return;
        }
        for (GraphSnapshot snapshot : snapshots) {
            snapshotPersistenceService.save(snapshot);
        }
    }

    /**
     * Pushes the current graph on a fixed cadence so clients observe time-based
     * metric decay (e.g. edge load level calming down) even when no new telemetry
     * mutates in-memory state.
     */
    @Scheduled(fixedRateString = "${kubevizor.snapshot-persist-interval-millis:1000}")
    public void publishCurrentSnapshots() {
        if (emitters.isEmpty()) {
            return;
        }
        broadcast(graphStateManager.buildSnapshots());
    }

    private void broadcast(List<GraphSnapshot> snapshots) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("graph-update")
                        .data(eventPayload(snapshots)));
            } catch (IOException e) {
                emitter.complete();
                emitters.remove(emitter);
            }
        }
    }

    private static @NonNull Object eventPayload(List<GraphSnapshot> snapshots) {
        return Objects.requireNonNull(snapshots, "snapshots");
    }
}
