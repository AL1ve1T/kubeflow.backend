package com.kubevizor.model;

import java.time.Instant;

/**
 * Normalized representation of one observed communication event derived from
 * telemetry.
 * This is the internal format after parsing and normalizing raw spans.
 */
public record InteractionEvent(
        String traceId,
        String spanId,
        String sourceService,
        String sourceNamespace,
        String targetService,
        String targetNamespace,
        NodeType targetType,
        String protocol,
        double latencyMs,
        boolean isError,
        Instant timestamp) {
}
