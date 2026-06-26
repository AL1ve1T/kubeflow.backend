package com.kubevizor.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Represents the resource-pressure level of a node, derived from CPU and memory
 * utilization.
 * Assigned to incoming edges so the frontend can visualize load on each
 * communication path.
 */
@Schema(description = "Resource-pressure level of the target node, derived from its CPU and memory utilization reported by the kubeletstats OTel receiver. Applied to all incoming edges of the node.")
public enum LoadLevel {
    /** CPU < 50% AND memory < 60%. Edge should be rendered green. */
    NORMAL,
    /** CPU ≥ 50% OR memory ≥ 60%. Edge should be rendered yellow. */
    ELEVATED,
    /** CPU ≥ 70% OR memory ≥ 75%. Edge should be rendered orange. */
    HIGH,
    /** CPU ≥ 85% OR memory ≥ 90%. Edge should be rendered red. */
    CRITICAL
}
