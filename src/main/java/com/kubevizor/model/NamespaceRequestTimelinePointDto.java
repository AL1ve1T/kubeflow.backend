package com.kubevizor.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A single namespace-level request-load sample at a point in time.")
public record NamespaceRequestTimelinePointDto(
                @Schema(description = "Timestamp of the graph snapshot this sample was read from.") Instant timestamp,
                @Schema(description = "Sum of requestsPerSecond across all edges in this namespace snapshot.", example = "42.0") double totalRequests,
                @Schema(description = "Total pod replicas across all workloads in the namespace at this instant. "
                                + "Zero for historical points captured before pod-readiness tracking existed (never null).", example = "12") int totalPods,
                @Schema(description = "Pods that are NOT in a ready/running state at this instant — i.e. any pod phase "
                                + "other than RUNNING (PENDING, NOT_READY, CRASH_LOOP, FAILED, UNKNOWN). PENDING is counted as "
                                + "not-ready (no duration-based exclusion) because each point samples instantaneous phase. "
                                + "Always satisfies 0 <= notReadyPods <= totalPods.", example = "3") int notReadyPods) {
}