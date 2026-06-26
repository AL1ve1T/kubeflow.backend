package com.kubevizor.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1")
@Tag(name = "Ingestion", description = "OTLP/HTTP trace ingestion")
public class OtlpTraceIngestionController {

    private static final Logger log = LoggerFactory.getLogger(OtlpTraceIngestionController.class);

    private final IngestionPipeline ingestionPipeline;
    private final ObjectMapper objectMapper;
    private final GraphUpdatePublisher graphUpdatePublisher;

    public OtlpTraceIngestionController(IngestionPipeline ingestionPipeline,
            ObjectMapper objectMapper,
            GraphUpdatePublisher graphUpdatePublisher) {
        this.ingestionPipeline = ingestionPipeline;
        this.objectMapper = objectMapper;
        this.graphUpdatePublisher = graphUpdatePublisher;
    }

    @Operation(summary = "Receive OTLP trace payload", description = """
            Accepts an OTLP/HTTP JSON trace export (`ExportTraceServiceRequest`).
            Spans are parsed, normalized into internal `InteractionEvent` objects, and aggregated
            into the live topology graph.

            **What is extracted per span:**
            - Source service name (from `service.name` resource attribute)
            - Target service/database name (from `db.name`, `peer.service`, or `net.peer.name`)
            - Protocol (HTTP, postgresql, redis, etc.)
            - Latency (span duration)
            - Error flag (`otel.status_code = ERROR` or HTTP 5xx)

            Edges and nodes that do not appear in a span for more than the configured stale threshold
            are automatically removed from the live graph.
            """)
    @ApiResponse(responseCode = "200", description = "Payload accepted and processed")
    @ApiResponse(responseCode = "400", description = "Payload could not be parsed")
    @PostMapping(value = "/traces", consumes = {
            MediaType.APPLICATION_JSON_VALUE,
            "application/x-protobuf",
            MediaType.APPLICATION_OCTET_STREAM_VALUE
    })
    public ResponseEntity<Void> receiveTraces(HttpServletRequest request,
            @RequestBody(required = false) byte[] rawBody) {
        Map<String, Object> payload;
        try {
            payload = parsePayload(request, rawBody);
        } catch (IOException e) {
            log.warn("Failed to parse OTLP traces payload", e);
            return ResponseEntity.badRequest().build();
        }

        log.debug("Received OTLP trace payload");
        ingestionPipeline.process(payload);
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
