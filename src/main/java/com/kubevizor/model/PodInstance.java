package com.kubevizor.model;

import java.time.Instant;
import java.util.Objects;
import org.springframework.lang.Nullable;

/**
 * Represents a single pod replica backing a workload {@link Node}.
 * Pods are the per-replica detail inside the replica-set wrapper: each one
 * carries its own resource utilization (from kubeletstats) and health (from the
 * Kubernetes API scraper). Traffic is never attributed to individual pods — it
 * stays on the workload edge — so this type holds no traffic metrics.
 */
public class PodInstance {

    private final String podName;
    // Resource utilization in [0.0, 1.0]; 0.0 means no data received yet
    private volatile double cpuUtilization;
    private volatile double memoryUtilization;
    @Nullable
    private volatile Instant lastCpuUpdatedAt = null;
    @Nullable
    private volatile Instant lastMemoryUpdatedAt = null;

    private volatile PodPhase podPhase = PodPhase.UNKNOWN;
    private volatile int restartCount = 0;
    @Nullable
    private volatile Instant lastRestartAt = null;
    @Nullable
    private volatile String lastRestartReason = null;

    private volatile Instant lastSeenAt;

    public PodInstance(String podName) {
        this.podName = Objects.requireNonNull(podName);
        this.lastSeenAt = Instant.now();
    }

    public String getPodName() {
        return podName;
    }

    public double getCpuUtilization() {
        return cpuUtilization;
    }

    public void setCpuUtilization(double cpuUtilization) {
        this.cpuUtilization = cpuUtilization;
        this.lastCpuUpdatedAt = Instant.now();
        this.lastSeenAt = this.lastCpuUpdatedAt;
    }

    public double getMemoryUtilization() {
        return memoryUtilization;
    }

    public void setMemoryUtilization(double memoryUtilization) {
        this.memoryUtilization = memoryUtilization;
        this.lastMemoryUpdatedAt = Instant.now();
        this.lastSeenAt = this.lastMemoryUpdatedAt;
    }

    @Nullable
    public Instant getLastCpuUpdatedAt() {
        return lastCpuUpdatedAt;
    }

    @Nullable
    public Instant getLastMemoryUpdatedAt() {
        return lastMemoryUpdatedAt;
    }

    public PodPhase getPodPhase() {
        return podPhase;
    }

    public int getRestartCount() {
        return restartCount;
    }

    @Nullable
    public Instant getLastRestartAt() {
        return lastRestartAt;
    }

    @Nullable
    public String getLastRestartReason() {
        return lastRestartReason;
    }

    public void setStatus(PodPhase podPhase, int restartCount,
            @Nullable Instant lastRestartAt, @Nullable String lastRestartReason) {
        this.podPhase = podPhase;
        this.restartCount = restartCount;
        this.lastRestartAt = lastRestartAt;
        this.lastRestartReason = lastRestartReason;
        this.lastSeenAt = Instant.now();
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        return podName.equals(((PodInstance) o).podName);
    }

    @Override
    public int hashCode() {
        return podName.hashCode();
    }
}
