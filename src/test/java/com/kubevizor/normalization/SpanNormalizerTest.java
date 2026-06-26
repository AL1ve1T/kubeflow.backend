package com.kubevizor.normalization;

import com.kubevizor.model.InteractionEvent;
import com.kubevizor.model.NodeType;
import com.kubevizor.parsing.ParsedSpan;
import com.kubevizor.topology.PodIpResolver;
import com.kubevizor.topology.TopologyResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpanNormalizerTest {

    private final TopologyResolver topologyResolver = new TopologyResolver();
    private final PodIpResolver podIpResolver = new PodIpResolver();
    private final SpanNormalizer normalizer = new SpanNormalizer(topologyResolver, podIpResolver);

    @Test
    void normalize_clientSpanWithPeerService_producesInteractionEvent() {
        ParsedSpan span = new ParsedSpan(
                "trace1", "span1", "parent1",
                "order-service", "demo",
                "GET /tickets", "3",
                1_000_000_000L, 1_050_000_000L,
                0,
                Map.of("peer.service", "ticket-service", "http.method", "GET"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);

        assertNotNull(event);
        assertEquals("order-service", event.sourceService());
        assertEquals("ticket-service", event.targetService());
        assertEquals(NodeType.SERVICE, event.targetType());
        assertEquals("HTTP", event.protocol());
        assertEquals(50.0, event.latencyMs(), 0.001);
        assertFalse(event.isError());
    }

    @Test
    void normalize_clientSpanWithDatabase_producesDbEvent() {
        ParsedSpan span = new ParsedSpan(
                "trace2", "span2", "parent2",
                "auth-service", "demo",
                "SELECT users", "3",
                1_000_000_000L, 1_020_000_000L,
                0,
                Map.of("db.system", "postgresql", "db.name", "authdb"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);

        assertNotNull(event);
        assertEquals("auth-service", event.sourceService());
        assertEquals("authdb", event.targetService());
        assertEquals(NodeType.DATABASE, event.targetType());
        assertEquals("postgresql", event.protocol());
    }

    @Test
    void normalize_serverSpanWithoutCallerInfo_attributesToExternal() {
        ParsedSpan span = new ParsedSpan(
                "trace3", "span3", "parent3",
                "ticket-service", "demo",
                "GET /tickets", "2",
                1_000_000_000L, 1_030_000_000L,
                0,
                Map.of("http.method", "GET"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);

        assertNotNull(event);
        assertEquals("external", event.sourceService());
        assertEquals("ticket-service", event.targetService());
        assertEquals(30.0, event.latencyMs(), 0.001);
    }

    @Test
    void normalize_serverSpanForNonHttpService_returnsNull() {
        // Beyla instruments postgres (port 5432) and emits a SERVER span.
        // No http.method, no method attribute — caller is unknowable, discard.
        ParsedSpan span = new ParsedSpan(
                "traceDb", "spanDb", "parentDb",
                "postgres", "default",
                "SELECT", "2",
                1_000_000_000L, 1_010_000_000L,
                0,
                Map.of("db.system", "postgresql"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);
        assertNull(event);
    }

    @Test
    void normalize_serverSpanForActuatorPath_returnsNull() {
        ParsedSpan span = new ParsedSpan(
                "trace3x", "span3x", "parent3x",
                "order-service", "demo",
                "http get /actuator/health", "2",
                1_000_000_000L, 1_005_000_000L,
                0,
                Map.of("method", "GET", "uri", "/actuator/health"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);
        assertNull(event);
    }

    @Test
    void normalize_serverSpanWithClientAddress_resolvesCallerViaIp() {
        podIpResolver.register("10.244.0.14", "order-service");

        ParsedSpan span = new ParsedSpan(
                "trace3b", "span3b", "parent3b",
                "ticket-service", "demo",
                "GET /tickets", "2",
                1_000_000_000L, 1_030_000_000L,
                0,
                Map.of("http.method", "GET", "client.address", "10.244.0.14"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);
        assertNotNull(event);
        assertEquals("order-service", event.sourceService());
        assertEquals("ticket-service", event.targetService());
    }

    @Test
    void normalize_serverSpanWithClientAddressAsWorkloadName_usesDirectly() {
        // Beyla resolves Kubernetes metadata and sets client.address to the workload
        // name instead of the raw pod IP when metadata is available.
        ParsedSpan span = new ParsedSpan(
                "trace3f", "span3f", "parent3f",
                "order-service", "demo",
                "GET /api/orders", "2",
                1_000_000_000L, 1_040_000_000L,
                0,
                Map.of("http.request.method", "GET", "client.address", "k6"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);

        assertNotNull(event);
        assertEquals("k6", event.sourceService());
        assertEquals("order-service", event.targetService());
    }

    @Test
    void normalize_serverSpanWithUnknownClientAddress_attributesToExternal() {
        ParsedSpan span = new ParsedSpan(
                "trace3c", "span3c", "parent3c",
                "ticket-service", "demo",
                "GET /tickets", "2",
                1_000_000_000L, 1_030_000_000L,
                0,
                Map.of("http.method", "GET", "client.address", "10.244.0.99"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);

        assertNotNull(event);
        assertEquals("external", event.sourceService());
        assertEquals("ticket-service", event.targetService());
    }

    @Test
    void normalize_serverSpanWithNewSemconvHttpRequestMethod_attributesToExternal() {
        // Beyla latest uses http.request.method (OTel HTTP semconv v1.20+)
        ParsedSpan span = new ParsedSpan(
                "trace3d", "span3d", "parent3d",
                "order-service", "demo",
                "GET /api/orders", "2",
                1_000_000_000L, 1_040_000_000L,
                0,
                Map.of("http.request.method", "GET", "url.path", "/api/orders"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);

        assertNotNull(event);
        assertEquals("external", event.sourceService());
        assertEquals("order-service", event.targetService());
        assertEquals(40.0, event.latencyMs(), 0.001);
    }

    @Test
    void normalize_serverSpanWithClientAddressAndPort_stripsPortBeforeResolving() {
        podIpResolver.register("10.244.0.14", "order-service");

        ParsedSpan span = new ParsedSpan(
                "trace3e", "span3e", "parent3e",
                "ticket-service", "demo",
                "POST /tickets", "2",
                1_000_000_000L, 1_025_000_000L,
                0,
                Map.of("http.request.method", "POST", "client.address", "10.244.0.14:54321"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);

        assertNotNull(event);
        assertEquals("order-service", event.sourceService());
        assertEquals("ticket-service", event.targetService());
    }

    @Test
    void normalize_errorSpan_setsIsErrorTrue() {
        ParsedSpan span = new ParsedSpan(
                "trace4", "span4", "parent4",
                "order-service", "demo",
                "POST /orders", "3",
                1_000_000_000L, 1_100_000_000L,
                2,
                Map.of("peer.service", "payment-service", "http.method", "POST"),
                Map.of());

        InteractionEvent event = normalizer.normalize(span);

        assertNotNull(event);
        assertTrue(event.isError());
        assertEquals(100.0, event.latencyMs(), 0.001);
    }
}
