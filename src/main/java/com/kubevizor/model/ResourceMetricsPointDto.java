package com.kubevizor.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A single CPU and memory utilization sample for a node at a point in time.")
public record ResourceMetricsPointDto(
        @Schema(description = "Timestamp of the graph snapshot this sample was read from.") Instant timestamp,
        @Schema(description = "CPU utilization in [0.0, 1.0] at this timestamp.", example = "0.42") double cpuUtilization,
        @Schema(description = "Memory utilization in [0.0, 1.0] at this timestamp.", example = "0.67") double memoryUtilization) {
}
