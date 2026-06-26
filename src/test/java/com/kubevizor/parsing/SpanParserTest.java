package com.kubevizor.parsing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpanParserTest {

        private final SpanParser parser = new SpanParser();

        @Test
        void parseSpans_extractsSpanFromOtlpPayload() {
                Map<String, Object> payload = Map.of("resourceSpans", List.of(
                                Map.of(
                                                "resource", Map.of("attributes", List.of(
                                                                Map.of("key", "service.name", "value",
                                                                                Map.of("stringValue", "order-service")),
                                                                Map.of("key", "service.namespace", "value",
                                                                                Map.of("stringValue", "demo")))),
                                                "scopeSpans", List.of(Map.of(
                                                                "spans", List.of(Map.of(
                                                                                "traceId", "abc123",
                                                                                "spanId", "span456",
                                                                                "parentSpanId", "parentSpan",
                                                                                "name", "GET /api/orders",
                                                                                "kind", "3",
                                                                                "startTimeUnixNano", "1000000000",
                                                                                "endTimeUnixNano", "1050000000",
                                                                                "status", Map.of("code", 0),
                                                                                "attributes", List.of(
                                                                                                Map.of("key", "http.method",
                                                                                                                "value",
                                                                                                                Map.of("stringValue",
                                                                                                                                "GET")),
                                                                                                Map.of("key", "peer.service",
                                                                                                                "value",
                                                                                                                Map.of("stringValue",
                                                                                                                                "ticket-service"))))))))));

                List<ParsedSpan> spans = parser.parseSpans(payload);

                assertEquals(1, spans.size());
                ParsedSpan span = spans.get(0);
                assertEquals("abc123", span.traceId());
                assertEquals("span456", span.spanId());
                assertEquals("order-service", span.serviceName());
                assertEquals("demo", span.serviceNamespace());
                assertEquals("GET /api/orders", span.spanName());
                assertTrue(span.isClientSpan());
                assertFalse(span.isError());
                assertEquals(50.0, span.durationMs(), 0.001);
                assertEquals("GET", span.attributes().get("http.method"));
                assertEquals("ticket-service", span.attributes().get("peer.service"));
        }

        @Test
        void parseSpans_handlesEmptyPayload() {
                List<ParsedSpan> spans = parser.parseSpans(Map.of());
                assertTrue(spans.isEmpty());
        }

        @Test
        void parseSpans_handlesErrorStatus() {
                Map<String, Object> payload = Map.of("resourceSpans", List.of(
                                Map.of(
                                                "resource", Map.of("attributes", List.of(
                                                                Map.of("key", "service.name", "value",
                                                                                Map.of("stringValue",
                                                                                                "auth-service")))),
                                                "scopeSpans", List.of(Map.of(
                                                                "spans", List.of(Map.of(
                                                                                "traceId", "trace1",
                                                                                "spanId", "span1",
                                                                                "name", "POST /login",
                                                                                "kind", "2",
                                                                                "startTimeUnixNano", "1000000000",
                                                                                "endTimeUnixNano", "1100000000",
                                                                                "status", Map.of("code", 2),
                                                                                "attributes", List.of())))))));

                List<ParsedSpan> spans = parser.parseSpans(payload);

                assertEquals(1, spans.size());
                assertTrue(spans.get(0).isError());
                assertTrue(spans.get(0).isServerSpan());
                assertEquals(100.0, spans.get(0).durationMs(), 0.001);
        }

        @Test
        void parseSpans_handlesStatusCodeErrorAsString() {
                // Some OTel SDKs serialise the status enum as its string name
                Map<String, Object> payload = Map.of("resourceSpans", List.of(
                                Map.of(
                                                "resource", Map.of("attributes", List.of(
                                                                Map.of("key", "service.name", "value",
                                                                                Map.of("stringValue",
                                                                                                "order-service")))),
                                                "scopeSpans", List.of(Map.of(
                                                                "spans", List.of(Map.of(
                                                                                "traceId", "t1", "spanId", "s1", "name",
                                                                                "POST /order",
                                                                                "kind", "3",
                                                                                "startTimeUnixNano", "1000000000",
                                                                                "endTimeUnixNano", "1050000000",
                                                                                "status",
                                                                                Map.of("code", "STATUS_CODE_ERROR"),
                                                                                "attributes", List.of())))))));

                List<ParsedSpan> spans = parser.parseSpans(payload);

                assertEquals(1, spans.size());
                assertTrue(spans.get(0).isError());
        }

        @Test
        void parseSpans_treatsHttp5xxAsError_whenOtelStatusUnset() {
                // Beyla and many libraries set http.status_code without setting OTel span
                // status
                Map<String, Object> statusAttr = Map.of(
                                "key", "http.status_code",
                                "value", Map.of("stringValue", "503"));
                Map<String, Object> span = Map.of(
                                "traceId", "t2", "spanId", "s2", "name", "GET /tickets",
                                "kind", "2",
                                "startTimeUnixNano", "1000000000", "endTimeUnixNano", "1050000000",
                                "status", Map.of("code", 0),
                                "attributes", List.of(statusAttr));
                Map<String, Object> payload = wrapInResourceSpan("ticket-service", span);

                List<ParsedSpan> spans = parser.parseSpans(payload);

                assertEquals(1, spans.size());
                assertTrue(spans.get(0).isError());
        }

        @Test
        void parseSpans_treatsHttp2xxAsNotError() {
                Map<String, Object> statusAttr = Map.of(
                                "key", "http.status_code",
                                "value", Map.of("stringValue", "200"));
                Map<String, Object> span = Map.of(
                                "traceId", "t3", "spanId", "s3", "name", "GET /tickets",
                                "kind", "2",
                                "startTimeUnixNano", "1000000000", "endTimeUnixNano", "1050000000",
                                "status", Map.of("code", 0),
                                "attributes", List.of(statusAttr));
                Map<String, Object> payload = wrapInResourceSpan("ticket-service", span);

                List<ParsedSpan> spans = parser.parseSpans(payload);

                assertEquals(1, spans.size());
                assertFalse(spans.get(0).isError());
        }

        @Test
        void parseSpans_treatsHttp5xxAsError_whenBeylaUsesStatusAttribute() {
                // Beyla sets the attribute key "status" (not "http.status_code")
                Map<String, Object> statusAttr = Map.of(
                                "key", "status",
                                "value", Map.of("stringValue", "503"));
                Map<String, Object> span = Map.of(
                                "traceId", "t5", "spanId", "s5", "name", "POST /auth/validate",
                                "kind", "2",
                                "startTimeUnixNano", "1000000000", "endTimeUnixNano", "1050000000",
                                "status", Map.of("code", 0),
                                "attributes", List.of(statusAttr));
                Map<String, Object> payload = wrapInResourceSpan("auth-service", span);

                List<ParsedSpan> spans = parser.parseSpans(payload);

                assertEquals(1, spans.size());
                assertTrue(spans.get(0).isError());
        }

        @Test
        void parseSpans_treatsHttp5xxAsError_whenBeylaSetStatusOk() {
                // Beyla sets statusCode = 1 (STATUS_CODE_OK) on all spans but still
                // puts the real HTTP status in http.status_code. isError() must honour it.
                Map<String, Object> statusAttr = Map.of(
                                "key", "http.status_code",
                                "value", Map.of("stringValue", "500"));
                Map<String, Object> span = Map.of(
                                "traceId", "t4", "spanId", "s4", "name", "POST /orders",
                                "kind", "2",
                                "startTimeUnixNano", "1000000000", "endTimeUnixNano", "1050000000",
                                "status", Map.of("code", 1),
                                "attributes", List.of(statusAttr));
                Map<String, Object> payload = wrapInResourceSpan("order-service", span);

                List<ParsedSpan> spans = parser.parseSpans(payload);

                assertEquals(1, spans.size());
                assertTrue(spans.get(0).isError());
        }

        /** Wraps a single span map into the minimal OTLP resourceSpans structure. */
        private Map<String, Object> wrapInResourceSpan(String serviceName, Map<String, Object> span) {
                return Map.of("resourceSpans", List.of(
                                Map.of(
                                                "resource", Map.of("attributes", List.of(
                                                                Map.of("key", "service.name", "value",
                                                                                Map.of("stringValue", serviceName)))),
                                                "scopeSpans", List.of(Map.of(
                                                                "spans", List.of(span))))));
        }

        @Test
        void parseSpans_extractsMultipleSpans() {
                Map<String, Object> payload = Map.of("resourceSpans", List.of(
                                Map.of(
                                                "resource", Map.of("attributes", List.of(
                                                                Map.of("key", "service.name", "value",
                                                                                Map.of("stringValue", "gateway")))),
                                                "scopeSpans", List.of(Map.of(
                                                                "spans", List.of(
                                                                                Map.of("traceId", "t1", "spanId", "s1",
                                                                                                "name", "call-a",
                                                                                                "kind", "3",
                                                                                                "startTimeUnixNano",
                                                                                                "1000000000",
                                                                                                "endTimeUnixNano",
                                                                                                "1020000000",
                                                                                                "status", Map.of(),
                                                                                                "attributes",
                                                                                                List.of()),
                                                                                Map.of("traceId", "t1", "spanId", "s2",
                                                                                                "name", "call-b",
                                                                                                "kind", "3",
                                                                                                "startTimeUnixNano",
                                                                                                "2000000000",
                                                                                                "endTimeUnixNano",
                                                                                                "2030000000",
                                                                                                "status", Map.of(),
                                                                                                "attributes",
                                                                                                List.of())))))));

                List<ParsedSpan> spans = parser.parseSpans(payload);
                assertEquals(2, spans.size());
        }
}
