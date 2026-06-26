package com.kubevizor.api;

import com.kubevizor.aggregation.GraphStateManager;
import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.NamespaceRequestTimelinePointDto;
import com.kubevizor.model.RequestRatePointDto;
import com.kubevizor.model.ResourceMetricsPointDto;
import com.kubevizor.model.RestartEventDto;
import com.kubevizor.persistence.NamespaceRequestTimelineService;
import com.kubevizor.persistence.NodeMetricsTimelineService;
import com.kubevizor.persistence.RestartTimelineService;
import com.kubevizor.persistence.SnapshotPersistenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Graph", description = "Live service topology graph")
public class GraphController {

    private final GraphStateManager graphStateManager;
    private final GraphUpdatePublisher graphUpdatePublisher;
    private final SnapshotPersistenceService snapshotPersistenceService;
    private final RestartTimelineService restartTimelineService;
    private final NodeMetricsTimelineService nodeMetricsTimelineService;
    private final NamespaceRequestTimelineService namespaceRequestTimelineService;

    public GraphController(GraphStateManager graphStateManager,
            GraphUpdatePublisher graphUpdatePublisher,
            SnapshotPersistenceService snapshotPersistenceService,
            RestartTimelineService restartTimelineService,
            NodeMetricsTimelineService nodeMetricsTimelineService,
            NamespaceRequestTimelineService namespaceRequestTimelineService) {
        this.graphStateManager = graphStateManager;
        this.graphUpdatePublisher = graphUpdatePublisher;
        this.snapshotPersistenceService = snapshotPersistenceService;
        this.restartTimelineService = restartTimelineService;
        this.nodeMetricsTimelineService = nodeMetricsTimelineService;
        this.namespaceRequestTimelineService = namespaceRequestTimelineService;
    }

    @Operation(summary = "Get current graph snapshots", description = "Returns the live topology as an array of per-namespace GraphSnapshot objects. Each node carries CPU/memory utilization; each edge carries RPS, latency, error rate, and a `loadLevel` derived from the target node's resource pressure.")
    @ApiResponse(responseCode = "200", description = "Current topology graph — one GraphSnapshot per namespace", content = @Content(array = @ArraySchema(schema = @Schema(implementation = GraphSnapshot.class))))
    @GetMapping(value = "/graph", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<GraphSnapshot> getSnapshots(
            @Parameter(description = "Filter to a specific namespace. Omit to receive all namespaces.") @RequestParam(required = false) String namespace) {
        if (namespace != null) {
            return List.of(graphStateManager.buildSnapshot(namespace));
        }
        return graphStateManager.buildSnapshots();
    }

    @Operation(summary = "Get historical graph snapshots", description = "Returns persisted snapshots within the requested time range (rolling 24-hour retention). Useful for replaying topology changes or debugging transient spikes.")
    @ApiResponse(responseCode = "200", description = "List of historical topology snapshots ordered by capture time", content = @Content(array = @ArraySchema(schema = @Schema(implementation = GraphSnapshot.class))))
    @GetMapping(value = "/graph/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<GraphSnapshot> getHistory(
            @Parameter(description = "Start of time range (ISO-8601). Defaults to 1 hour ago.") @RequestParam(required = false) Instant from,
            @Parameter(description = "End of time range (ISO-8601). Defaults to now.") @RequestParam(required = false) Instant to,
            @Parameter(description = "Filter to a specific namespace.") @RequestParam(required = false) String namespace) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(1, ChronoUnit.HOURS);
        if (namespace != null) {
            return snapshotPersistenceService.getHistory(start, end, namespace);
        }
        return snapshotPersistenceService.getHistory(start, end);
    }

    @Operation(summary = "Stream live graph updates via SSE", description = "Server-Sent Events stream that pushes a `graph-update` event whenever the topology or metrics change. **Connect once; the server pushes updates automatically.** The initial event is sent immediately on connect and contains the full current graph state. Subsequent events are emitted after every ingestion batch that changes the graph. Each event `data` field is a JSON array of `GraphSnapshot` — one object per namespace.")
    @ApiResponse(responseCode = "200", description = "SSE stream. Each event: `event: graph-update\\ndata: GraphSnapshot[]`", content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(implementation = GraphSnapshot.class)))
    @GetMapping(value = "/graph/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUpdates() {
        return graphUpdatePublisher.subscribe();
    }

    @Operation(summary = "Get container restart timeline for a node", description = "Queries the persisted graph-snapshot history to reconstruct a chronological list of container restart events for the specified workload node. Each event includes the detection timestamp, the Kubernetes termination timestamp (`restartAt`), the termination reason (e.g. `OOMKilled`), the cumulative restart count, the count delta since the previous event, and `recoveredAt` — the first snapshot timestamp in which the node was observed as RUNNING again (null if still down at the end of the queried window). The default time range covers the full 30-day retention window.")
    @ApiResponse(responseCode = "200", description = "Restart events ordered by detection time ascending", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RestartEventDto.class))))
    @GetMapping(value = "/nodes/{nodeId}/restarts", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RestartEventDto> getRestartTimeline(
            @Parameter(description = "Workload node ID (e.g. order-service).", example = "order-service") @PathVariable String nodeId,
            @Parameter(description = "Start of time range (ISO-8601). Defaults to 30 days ago.") @RequestParam(required = false) Instant from,
            @Parameter(description = "End of time range (ISO-8601). Defaults to now.") @RequestParam(required = false) Instant to,
            @Parameter(description = "Kubernetes namespace to search. Defaults to 'default'.") @RequestParam(defaultValue = "default") String namespace) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
        return restartTimelineService.getRestartTimeline(nodeId, namespace, start, end);
    }

    @Operation(summary = "Get CPU and memory utilization history for a node", description = "Returns a time-series of CPU and memory utilization samples derived from the persisted graph-snapshot history. Each point corresponds to one snapshot in which the node was present. The default time range covers the last hour.")
    @ApiResponse(responseCode = "200", description = "CPU/memory samples ordered by timestamp ascending", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ResourceMetricsPointDto.class))))
    @GetMapping(value = "/nodes/{nodeId}/resource-metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ResourceMetricsPointDto> getResourceMetrics(
            @Parameter(description = "Workload node ID (e.g. order-service).", example = "order-service") @PathVariable String nodeId,
            @Parameter(description = "Start of time range (ISO-8601). Defaults to 1 hour ago.") @RequestParam(required = false) Instant from,
            @Parameter(description = "End of time range (ISO-8601). Defaults to now.") @RequestParam(required = false) Instant to,
            @Parameter(description = "Kubernetes namespace to search. Defaults to 'default'.") @RequestParam(defaultValue = "default") String namespace) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(1, ChronoUnit.HOURS);
        return nodeMetricsTimelineService.getResourceMetrics(nodeId, namespace, start, end);
    }

    @Operation(summary = "Get inbound request-rate history for a node", description = "Returns a time-series of inbound requests-per-second values derived from the persisted graph-snapshot history. Each point is the sum of requestsPerSecond across all edges targeting this node in that snapshot. The default time range covers the last hour.")
    @ApiResponse(responseCode = "200", description = "Request-rate samples ordered by timestamp ascending", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RequestRatePointDto.class))))
    @GetMapping(value = "/nodes/{nodeId}/request-rate", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RequestRatePointDto> getRequestRate(
            @Parameter(description = "Workload node ID (e.g. order-service).", example = "order-service") @PathVariable String nodeId,
            @Parameter(description = "Start of time range (ISO-8601). Defaults to 1 hour ago.") @RequestParam(required = false) Instant from,
            @Parameter(description = "End of time range (ISO-8601). Defaults to now.") @RequestParam(required = false) Instant to,
            @Parameter(description = "Kubernetes namespace to search. Defaults to 'default'.") @RequestParam(defaultValue = "default") String namespace) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(1, ChronoUnit.HOURS);
        return nodeMetricsTimelineService.getRequestRate(nodeId, namespace, start, end);
    }

    @Operation(summary = "Get namespace request timeline", description = "Returns a lightweight time-series for the namespace timeline view. Each point contains the snapshot timestamp, total requests (sum of edge requestsPerSecond in that snapshot), and pod-readiness counts (totalPods, notReadyPods) sampled on the same timestamp grid so the curve can be coloured by pod health. A pod is 'not ready' if its phase is anything other than RUNNING; historical points predating pod tracking report 0/0.")
    @ApiResponse(responseCode = "200", description = "Namespace request timeline ordered by timestamp ascending", content = @Content(array = @ArraySchema(schema = @Schema(implementation = NamespaceRequestTimelinePointDto.class))))
    @GetMapping(value = "/namespaces/{namespace}/request-timeline", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<NamespaceRequestTimelinePointDto> getNamespaceRequestTimeline(
            @Parameter(description = "Kubernetes namespace to query.", example = "default") @PathVariable String namespace,
            @Parameter(description = "Start of time range (ISO-8601). Defaults to 1 hour ago.") @RequestParam(required = false) Instant from,
            @Parameter(description = "End of time range (ISO-8601). Defaults to now.") @RequestParam(required = false) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(1, ChronoUnit.HOURS);
        return namespaceRequestTimelineService.getTimeline(namespace, start, end);
    }
}
