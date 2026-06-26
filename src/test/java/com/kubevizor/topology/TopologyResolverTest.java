package com.kubevizor.topology;

import com.kubevizor.model.NodeType;
import com.kubevizor.parsing.ParsedSpan;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TopologyResolverTest {

    private final TopologyResolver resolver = new TopologyResolver();

    @Test
    void resolveTarget_withPeerService_returnsServiceNode() {
        ParsedSpan span = new ParsedSpan(
                "t1", "s1", "p1", "src", "ns",
                "GET /api", "3", 0, 100, 0,
                Map.of("peer.service", "target-svc"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("target-svc", target.serviceName());
        assertEquals(NodeType.SERVICE, target.nodeType());
    }

    @Test
    void resolveTarget_withPeerServiceKubernetes_returnsNull() {
        ParsedSpan span = new ParsedSpan(
                "t1k", "s1k", "p1k", "src", "ns",
                "GET /api", "3", 0, 100, 0,
                Map.of("peer.service", "kubernetes"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNull(target);
    }

    @Test
    void resolveTarget_withDbSystem_returnsDatabaseNode() {
        ParsedSpan span = new ParsedSpan(
                "t2", "s2", "p2", "src", "ns",
                "SELECT *", "3", 0, 100, 0,
                Map.of("db.system", "mysql", "db.name", "orders"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("orders", target.serviceName());
        assertEquals(NodeType.DATABASE, target.nodeType());
    }

    @Test
    void resolveTarget_withServerAddress_returnsServiceNode() {
        ParsedSpan span = new ParsedSpan(
                "t3", "s3", "p3", "src", "ns",
                "call", "3", 0, 100, 0,
                Map.of("server.address", "auth-service:8080"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("auth-service", target.serviceName());
        assertEquals(NodeType.SERVICE, target.nodeType());
    }

    @Test
    void resolveTarget_withKubernetesApiAddress_returnsNull() {
        ParsedSpan span = new ParsedSpan(
                "t3k", "s3k", "p3k", "src", "ns",
                "call", "3", 0, 100, 0,
                Map.of("server.address", "kubernetes.default.svc:443"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNull(target);
    }

    @Test
    void resolveTarget_withHttpUrl_returnsExternalNode() {
        ParsedSpan span = new ParsedSpan(
                "t4", "s4", "p4", "src", "ns",
                "call", "3", 0, 100, 0,
                Map.of("http.url", "https://api.example.com/v1/data"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("api.example.com", target.serviceName());
        assertEquals(NodeType.INPUT, target.nodeType());
    }

    @Test
    void resolveTarget_withNoAttributes_fallsBackToSpanName() {
        ParsedSpan span = new ParsedSpan(
                "t5", "s5", "p5", "src", "ns",
                "unknown-target", "3", 0, 100, 0,
                Map.of(),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("unknown-target", target.serviceName());
        assertEquals(NodeType.INPUT, target.nodeType());
    }

    @Test
    void resolveTarget_dbSystemWithoutDbName_usesSystemAsName() {
        ParsedSpan span = new ParsedSpan(
                "t6", "s6", "p6", "src", "ns",
                "query", "3", 0, 100, 0,
                Map.of("db.system", "redis"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("redis", target.serviceName());
        assertEquals(NodeType.CACHE, target.nodeType());
    }

    @Test
    void resolveTarget_withDbSystemName_returnsDatabaseNode() {
        ParsedSpan span = new ParsedSpan(
                "t7", "s7", "p7", "src", "ns",
                "PREPARED STATEMENT", "3", 0, 100, 0,
                Map.of("db.system.name", "postgresql", "db.namespace", "ticketdb"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("ticketdb", target.serviceName());
        assertEquals(NodeType.DATABASE, target.nodeType());
    }

    @Test
    void resolveTarget_withMessagingSystem_returnsQueueNode() {
        ParsedSpan span = new ParsedSpan(
                "t8", "s8", "p8", "src", "ns",
                "publish", "3", 0, 100, 0,
                Map.of("messaging.system", "kafka", "messaging.destination.name", "orders-topic"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("orders-topic", target.serviceName());
        assertEquals(NodeType.QUEUE, target.nodeType());
    }

    @Test
    void resolveTarget_withMessagingSystemNoDestination_usesSystemName() {
        ParsedSpan span = new ParsedSpan(
                "t9", "s9", "p9", "src", "ns",
                "publish", "3", 0, 100, 0,
                Map.of("messaging.system", "rabbitmq"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("rabbitmq", target.serviceName());
        assertEquals(NodeType.QUEUE, target.nodeType());
    }

    @Test
    void resolveTarget_withMemcachedSystem_returnsCacheNode() {
        ParsedSpan span = new ParsedSpan(
                "t10", "s10", "p10", "src", "ns",
                "get", "3", 0, 100, 0,
                Map.of("db.system", "memcached"),
                Map.of());

        TopologyResolver.ResolvedTarget target = resolver.resolveTarget(span);

        assertNotNull(target);
        assertEquals("memcached", target.serviceName());
        assertEquals(NodeType.CACHE, target.nodeType());
    }
}
