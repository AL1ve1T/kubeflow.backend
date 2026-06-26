package com.kubevizor.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * A single detected container restart event for a workload node, derived from
 * scanning the persisted graph-snapshot history.
 *
 * <p>
 * Because Kubernetes only retains the <em>most recent</em> termination per
 * container, each restart event reflects what was visible in the cluster at
 * {@code detectedAt}. If a workload crashes and recovers between two scrape
 * cycles only the final crash is recorded.
 */
@Schema(description = "A detected container restart event for a workload node, derived from the persisted graph snapshot history.")
public record RestartEventDto(

        @Schema(description = "Timestamp of the graph snapshot in which the restart was first detected (i.e. server-side capture time).") Instant detectedAt,

        @Schema(description = "Timestamp of the actual container termination as reported by Kubernetes. Null when the restart was inferred from a restartCount increase but Kubernetes no longer holds the termination record.") @Nullable Instant restartAt,

        @Schema(description = "Termination reason reported by Kubernetes, e.g. OOMKilled, Error, Completed. Null when the termination record is no longer available.", example = "OOMKilled") @Nullable String reason,

        @Schema(description = "Total cumulative restart count at the time this event was detected.", example = "7") int restartCount,

        @Schema(description = "Increase in restart count since the previous detected event. Useful for spotting crash storms between scrape intervals.", example = "1") int countDelta,

        @Schema(description = "Timestamp of the first snapshot in which the node was observed as RUNNING after this restart. Null if the node had not recovered within the queried time window.") @Nullable Instant recoveredAt) {
}
