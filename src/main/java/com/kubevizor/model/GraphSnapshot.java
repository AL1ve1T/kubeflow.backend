package com.kubevizor.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Frontend-facing representation of the current topology state.
 * Contains all nodes, edges, and metadata for the UI to render.
 */
@Schema(description = "Topology snapshot for a single Kubernetes namespace, emitted on every graph-update SSE event and returned by GET /api/graph.")
public record GraphSnapshot(
                @Schema(description = "Kubernetes namespace this snapshot covers.", example = "default") String namespace,
                @Schema(description = "All nodes (services, databases, caches, queues, etc.) visible in this namespace.") List<NodeDto> nodes,
                @Schema(description = "All directed communication edges between nodes, with aggregated traffic metrics.") List<EdgeDto> edges,
                @Schema(description = "Server-side timestamp when this snapshot was built.") Instant generatedAt) {

        @Schema(description = "A workload node in the topology graph.")
        public record NodeDto(
                        @Schema(description = "Stable node identifier — the Kubernetes workload / owner name.", example = "order-service") String id,
                        @Schema(description = "Display name for the node.", example = "order-service") String name,
                        @Schema(description = "Category of the node.") NodeType type,
                        @Schema(description = "Operational health state of the Kubernetes pod(s) backing this node, scraped from the Kubernetes API. UNKNOWN means no data received yet (synthetic nodes or scraper not running).") PodPhase podPhase,
                        @Schema(description = "Total container restart count across all pod replicas for this workload. Increases monotonically; a jump here indicates a crash or OOM kill.", example = "3") int restartCount,
                        @Schema(description = "Number of pod replicas observed for this workload during the last scrape. 0 means the scraper has not run yet or no pods were found.", example = "2") int podCount,
                        @Schema(description = "Timestamp of the most recent container termination (i.e. when the last restart was triggered). Null if the workload has never restarted.") @Nullable Instant lastRestartAt,
                        @Schema(description = "Reason for the most recent termination as reported by Kubernetes, e.g. OOMKilled, Error, Completed. Null if the workload has never restarted.", example = "OOMKilled") @Nullable String lastRestartReason,
                        @Schema(description = "Last time a span or network-flow event was observed involving this node.") Instant lastSeenAt,
                        @Schema(description = "Individual pod replicas backing this workload, each with its own CPU/memory utilization and health. Traffic is not attributed per pod — it stays on the workload edges. Empty if no pod-level data has been observed yet.") List<PodDto> pods) {
        }

        @Schema(description = "A single pod replica backing a workload node. Carries per-replica resource utilization and health, but no traffic metrics.")
        public record PodDto(
                        @Schema(description = "Kubernetes pod name.", example = "order-service-6d9f8b5c7-xk9p2") String podName,
                        @Schema(description = "CPU utilization in [0.0, 1.0] for this pod as reported by kubeletstats. 0.0 means no fresh data.", example = "0.42") double cpuUtilization,
                        @Schema(description = "Memory utilization in [0.0, 1.0] for this pod as reported by kubeletstats. 0.0 means no fresh data.", example = "0.67") double memoryUtilization,
                        @Schema(description = "Operational health state of this pod, scraped from the Kubernetes API.") PodPhase podPhase,
                        @Schema(description = "Container restart count for this pod.", example = "3") int restartCount,
                        @Schema(description = "Timestamp of the most recent container termination for this pod. Null if never restarted.") @Nullable Instant lastRestartAt,
                        @Schema(description = "Reason for the most recent termination for this pod, e.g. OOMKilled. Null if never restarted.", example = "OOMKilled") @Nullable String lastRestartReason,
                        @Schema(description = "Last time any signal was observed for this pod.") Instant lastSeenAt) {
        }

        @Schema(description = "A directed communication edge between two nodes, carrying rolling traffic metrics and a resource-pressure load level.")
        public record EdgeDto(
                        @Schema(description = "Edge identifier in the form 'source->target'.", example = "order-service->ticket-service") String id,
                        @Schema(description = "ID of the source node.", example = "order-service") String sourceNodeId,
                        @Schema(description = "ID of the target node.", example = "ticket-service") String targetNodeId,
                        @Schema(description = "Application protocol observed on this edge.", example = "HTTP", allowableValues = {
                                        "HTTP", "postgresql", "redis", "mysql", "mongodb", "kafka", "amqp", "cassandra",
                                        "elasticsearch",
                                        "TCP" }) String protocol,
                        @Schema(description = "Rolling requests-per-second rate computed over the current sliding window.", example = "12.5") double requestsPerSecond,
                        @Schema(description = "Average request latency in milliseconds over all observed requests.", example = "45.0") double averageLatencyMs,
                        @Schema(description = "Maximum observed request latency in milliseconds.", example = "320.0") double maxLatencyMs,
                        @Schema(description = "Total number of error responses observed.") long errorCount,
                        @Schema(description = "Error rate as a fraction of total requests in [0.0, 1.0].", example = "0.02") double errorRate,
                        @Schema(description = """
                                        Resource-pressure level of the target node, derived from its CPU and memory utilization.
                                        Use this field to colour edges in the topology visualization:
                                        NORMAL → green, ELEVATED → yellow, HIGH → orange, CRITICAL → red.
                                        """) LoadLevel loadLevel,
                        @Schema(description = "Last time a request was observed on this edge.") Instant lastSeenAt) {
        }
}
