package com.kubevizor.parsing;

import java.time.Instant;
import java.util.Map;

/**
 * Intermediate representation of a span extracted from an OTLP payload.
 * This is OpenTelemetry-specific; domain logic should use InteractionEvent
 * instead.
 */
public record ParsedSpan(
        String traceId,
        String spanId,
        String parentSpanId,
        String serviceName,
        String serviceNamespace,
        String spanName,
        String spanKind,
        long startTimeUnixNano,
        long endTimeUnixNano,
        int statusCode,
        Map<String, String> attributes,
        Map<String, String> resourceAttributes) {

    public double durationMs() {
        return (endTimeUnixNano - startTimeUnixNano) / 1_000_000.0;
    }

    public Instant startInstant() {
        return Instant.ofEpochSecond(0, startTimeUnixNano);
    }

    public boolean isError() {
        // Primary: OTel STATUS_CODE_ERROR (2)
        if (statusCode == 2)
            return true;

        // Secondary: HTTP status >= 400 always indicates an error regardless of OTel
        // status.
        // Beyla (eBPF instrumentation) sets statusCode = 1 (OK) on all spans and puts
        // the
        // real HTTP status in the attribute — so we must check this even when
        // statusCode == 1.
        // Beyla uses the attribute name "status" (not "http.status_code").
        String httpStatus = attributes.get("http.response.status_code"); // OTel semconv v1.20+
        if (httpStatus == null)
            httpStatus = attributes.get("http.status_code"); // legacy semconv
        if (httpStatus == null)
            httpStatus = attributes.get("status"); // Beyla eBPF instrumentation
        if (httpStatus != null) {
            try {
                return Integer.parseInt(httpStatus) >= 400;
            } catch (NumberFormatException ignored) {
            }
        }

        // Tertiary: Beyla sets outcome=FAILURE for failed requests (network errors,
        // timeouts)
        // that never produce an HTTP status code.
        String outcome = attributes.get("outcome");
        if ("FAILURE".equals(outcome))
            return true;

        return false;
    }

    public boolean isServerSpan() {
        return "SPAN_KIND_SERVER".equals(spanKind) || "2".equals(spanKind);
    }

    public boolean isClientSpan() {
        return "SPAN_KIND_CLIENT".equals(spanKind) || "3".equals(spanKind);
    }
}
