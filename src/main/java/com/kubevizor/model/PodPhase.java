package com.kubevizor.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Operational health state of a Kubernetes workload, as observed by scraping
 * the pod list from the Kubernetes API.
 *
 * When a deployment has multiple replicas, the worst-case pod phase is reported
 * so the frontend always shows the most degraded state.
 */
@Schema(description = "Operational health state of the Kubernetes workload backing this node.")
public enum PodPhase {

    /** All containers are ready; no restarts in this scrape window. */
    RUNNING,

    /** Pod is scheduled but containers have not started yet. */
    PENDING,

    /**
     * Containers exist but the readiness probe is failing (initializing or
     * degraded).
     */
    NOT_READY,

    /** At least one container is in CrashLoopBackOff or was OOMKilled. */
    CRASH_LOOP,

    /** Pod terminated with a non-zero exit code. */
    FAILED,

    /**
     * No pod data has been received yet — the scraper has not run or the node
     * represents a synthetic entity (external, internal) with no backing pod.
     */
    UNKNOWN;

    /**
     * Returns true if this phase represents more degradation than {@code other}.
     * Used when aggregating multiple pod replicas into a single workload status.
     */
    public boolean isWorseThan(PodPhase other) {
        return severity(this) > severity(other);
    }

    private static int severity(PodPhase p) {
        return switch (p) {
            case UNKNOWN -> -1;
            case RUNNING -> 0;
            case PENDING -> 1;
            case NOT_READY -> 2;
            case CRASH_LOOP -> 3;
            case FAILED -> 4;
        };
    }
}
