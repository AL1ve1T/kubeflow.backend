package com.kubevizor.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubevizor.api.GraphUpdatePublisher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/v1")
@Tag(name = "Ingestion", description = "OTLP/HTTP metrics ingestion")
public class OtlpMetricsIngestionController {

    private static final Logger log = LoggerFactory.getLogger(OtlpMetricsIngestionController.class);

    private final NetworkFlowProcessor networkFlowProcessor;
    private final ResourceMetricsProcessor resourceMetricsProcessor;
    private final ObjectMapper objectMapper;
    private final GraphUpdatePublisher graphUpdatePublisher;

    public OtlpMetricsIngestionController(NetworkFlowProcessor networkFlowProcessor,
            ResourceMetricsProcessor resourceMetricsProcessor,
            ObjectMapper objectMapper,
            GraphUpdatePublisher graphUpdatePublisher) {
        this.networkFlowProcessor = networkFlowProcessor;
        this.resourceMetricsProcessor = resourceMetricsProcessor;
        this.objectMapper = objectMapper;
        this.graphUpdatePublisher = graphUpdatePublisher;
    }

    @Operation(summary = "Receive OTLP metrics payload", description = """
            Accepts an OTLP/HTTP JSON metrics export. Two types of metrics are processed:

            **1. Beyla network flow metrics** (`beyla.network.flow.bytes`)
            Emitted by [Beyla](https://grafana.com/docs/beyla/) eBPF auto-instrumentation.
            Each data point describes bytes transferred between two Kubernetes workloads.
            The backend uses these to register topology edges.

            **2. Kubeletstats resource metrics** (`container.cpu.utilization`, `container.memory.utilization`)
            Emitted by the OpenTelemetry [kubeletstats receiver](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/receiver/kubeletstatsreceiver).
            The backend maps each metric to the matching workload node and uses the values to
            compute `loadLevel` on all incoming edges of that node.

            Payloads are processed in-place; individual unknown metric names are silently ignored.
            """)
    @ApiResponse(responseCode = "200", description = "Payload accepted and processed")
    @ApiResponse(responseCode = "400", description = "Payload could not be parsed")
    @PostMapping(value = "/metrics", consumes = {
            MediaType.APPLICATION_JSON_VALUE,
            "application/x-protobuf",
            MediaType.APPLICATION_OCTET_STREAM_VALUE
    })
    public ResponseEntity<Void> receiveMetrics(HttpServletRequest request,
            @RequestBody(required = false) byte[] rawBody) {
        Map<String, Object> payload;
        try {
            payload = parsePayload(request, rawBody);
        } catch (IOException e) {
            log.warn("Failed to parse OTLP metrics payload", e);
            return ResponseEntity.badRequest().build();
        }

        log.debug("Received OTLP metrics payload");
        networkFlowProcessor.processMetrics(payload);
        resourceMetricsProcessor.processMetrics(payload);
        graphUpdatePublisher.notifyIfChanged();
        return ResponseEntity.ok().build();
    }

    private Map<String, Object> parsePayload(HttpServletRequest request, byte[] rawBody) throws IOException {
        InputStream inputStream = rawBody != null
                ? new ByteArrayInputStream(rawBody)
                : request.getInputStream();
        String contentEncoding = request.getHeader("Content-Encoding");
        if (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip")) {
            inputStream = new GZIPInputStream(inputStream);
        }
        return objectMapper.readValue(inputStream, new TypeReference<>() {
        });
    }
}
