package com.kubevizor.model;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a directed edge between two nodes in the topology graph.
 * Contains aggregated rolling metrics for the communication link.
 *
 * Traffic metrics (RPS, average latency, error rate) are computed over a
 * 60-second sliding window of one-second buckets. Once traffic stops, all
 * windowed metrics fade to zero within 60 seconds.
 *
 * Lifetime counter (errorCount) accumulates for the full lifetime of the edge
 * and is kept for display purposes.
 */
public class Edge {

    private static final int WINDOW_SECONDS = 60;

    private final String id;
    private final String sourceNodeId;
    private final String targetNodeId;
    private String protocol;
    private Instant lastSeenAt;
    // Set only when an actual request span is recorded; null for topology-only
    // (Beyla) edges.
    private volatile Instant lastTrafficAt;

    // Lifetime counter – used for the DTO field errorCount
    private final AtomicLong errorCount = new AtomicLong(0);

    // 60-second ring buffer: one slot per second, indexed by epochSecond %
    // WINDOW_SECONDS
    private final long[] bucketRequests = new long[WINDOW_SECONDS];
    private final long[] bucketErrors = new long[WINDOW_SECONDS];
    private final double[] bucketLatency = new double[WINDOW_SECONDS]; // sum of latencies in the bucket
    private final double[] bucketMaxLatency = new double[WINDOW_SECONDS]; // max latency in the bucket
    // tracks which calendar second each ring slot covers; 0 means "empty"
    private final long[] bucketEpoch = new long[WINDOW_SECONDS];
    private final Object bucketLock = new Object();

    public Edge(String sourceNodeId, String targetNodeId, String protocol) {
        this.id = sourceNodeId + "->" + targetNodeId;
        this.sourceNodeId = Objects.requireNonNull(sourceNodeId);
        this.targetNodeId = Objects.requireNonNull(targetNodeId);
        this.protocol = protocol;
        this.lastSeenAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getLastTrafficAt() {
        return lastTrafficAt;
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    // ---- windowed metric accessors ----

    public double getRequestsPerSecond() {
        return getRequestsPerSecond(Instant.now());
    }

    public double getRequestsPerSecond(Instant snapshotTime) {
        long now = epochSecond(snapshotTime);
        long total = 0;
        synchronized (bucketLock) {
            for (int i = 0; i < WINDOW_SECONDS; i++) {
                if (now - bucketEpoch[i] < WINDOW_SECONDS) {
                    total += bucketRequests[i];
                }
            }
        }
        return (double) total / WINDOW_SECONDS;
    }

    public double getAverageLatencyMs() {
        return getAverageLatencyMs(Instant.now());
    }

    public double getAverageLatencyMs(Instant snapshotTime) {
        long now = epochSecond(snapshotTime);
        long reqs = 0;
        double lat = 0.0;
        synchronized (bucketLock) {
            for (int i = 0; i < WINDOW_SECONDS; i++) {
                if (now - bucketEpoch[i] < WINDOW_SECONDS) {
                    reqs += bucketRequests[i];
                    lat += bucketLatency[i];
                }
            }
        }
        return reqs > 0 ? lat / reqs : 0.0;
    }

    public double getMaxLatencyMs() {
        return getMaxLatencyMs(Instant.now());
    }

    public double getMaxLatencyMs(Instant snapshotTime) {
        long now = epochSecond(snapshotTime);
        double max = 0.0;
        synchronized (bucketLock) {
            for (int i = 0; i < WINDOW_SECONDS; i++) {
                if (now - bucketEpoch[i] < WINDOW_SECONDS && bucketMaxLatency[i] > max) {
                    max = bucketMaxLatency[i];
                }
            }
        }
        return max;
    }

    public double getErrorRate() {
        return getErrorRate(Instant.now());
    }

    public double getErrorRate(Instant snapshotTime) {
        long now = epochSecond(snapshotTime);
        long reqs = 0;
        long errs = 0;
        synchronized (bucketLock) {
            for (int i = 0; i < WINDOW_SECONDS; i++) {
                if (now - bucketEpoch[i] < WINDOW_SECONDS) {
                    reqs += bucketRequests[i];
                    errs += bucketErrors[i];
                }
            }
        }
        return reqs > 0 ? (double) errs / reqs : 0.0;
    }

    // ---- mutation ----

    public void recordRequest(double latencyMs, boolean isError) {
        if (isError) {
            errorCount.incrementAndGet();
        }
        long now = epochSecond();
        int idx = bucketIndex(now);
        synchronized (bucketLock) {
            if (bucketEpoch[idx] != now) {
                // slot belongs to an older second — reset it
                bucketRequests[idx] = 0;
                bucketErrors[idx] = 0;
                bucketLatency[idx] = 0.0;
                bucketMaxLatency[idx] = 0.0;
                bucketEpoch[idx] = now;
            }
            bucketRequests[idx]++;
            bucketLatency[idx] += latencyMs;
            if (latencyMs > bucketMaxLatency[idx]) {
                bucketMaxLatency[idx] = latencyMs;
            }
            if (isError) {
                bucketErrors[idx]++;
            }
        }
        this.lastSeenAt = Instant.now();
        this.lastTrafficAt = Instant.now();
    }

    public void touch() {
        this.lastSeenAt = Instant.now();
    }

    // ---- helpers ----

    private static long epochSecond() {
        return System.currentTimeMillis() / 1000;
    }

    private static long epochSecond(Instant instant) {
        return instant.getEpochSecond();
    }

    private static int bucketIndex(long epochSec) {
        return (int) (epochSec % WINDOW_SECONDS);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Edge edge = (Edge) o;
        return id.equals(edge.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Edge{%s, rps=%.2f, avgLatency=%.1fms, errorRate=%.2f}".formatted(
                id, getRequestsPerSecond(), getAverageLatencyMs(), getErrorRate());
    }
}
