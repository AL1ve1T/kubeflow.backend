package com.kubevizor.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A single inbound requests-per-second sample for a node at a point in time.")
public record RequestRatePointDto(
        @Schema(description = "Timestamp of the graph snapshot this sample was read from.") Instant timestamp,
        @Schema(description = "Sum of requestsPerSecond on all inbound edges to this node at this timestamp.", example = "12.5") double requestsPerSecond) {
}
