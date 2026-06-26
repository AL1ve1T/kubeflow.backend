package com.kubevizor.topology;

import com.kubevizor.model.NodeType;
import com.kubevizor.parsing.ParsedSpan;
import org.springframework.stereotype.Component;

/**
 * Resolves the target node for a given span based on span attributes.
 * Determines whether the target is a service, database, or external dependency.
 */
@Component
public class TopologyResolver {

    private static final java.util.Set<String> IGNORED_SERVICE_TARGETS = java.util.Set.of(
            "kubernetes",
            "kubernetes.default.svc",
            "kubernetes.default.svc.cluster.local");

    public record ResolvedTarget(
            String serviceName,
            String namespace,
            NodeType nodeType) {
    }

    // db.system values treated as CACHE rather than DATABASE
    private static final java.util.Set<String> CACHE_SYSTEMS = java.util.Set.of(
            "redis", "memcached", "hazelcast", "dragonfly");

    public ResolvedTarget resolveTarget(ParsedSpan span) {
        // Check for messaging/queue calls first
        ResolvedTarget queueTarget = resolveQueue(span);
        if (queueTarget != null)
            return queueTarget;

        // Check for database/cache calls
        ResolvedTarget dbTarget = resolveDatabase(span);
        if (dbTarget != null)
            return dbTarget;

        // Check for HTTP/RPC calls to other services
        ResolvedTarget serviceTarget = resolveService(span);
        if (serviceTarget != null)
            return serviceTarget;
        if (isIgnoredServiceCall(span)) {
            return null;
        }

        // Fall back to span name as target
        if (span.spanName() != null && !span.spanName().isBlank()) {
            return new ResolvedTarget(span.spanName(), null, NodeType.INPUT);
        }

        return null;
    }

    private ResolvedTarget resolveQueue(ParsedSpan span) {
        String messagingSystem = span.attributes().get("messaging.system");
        if (messagingSystem == null)
            return null;
        String destination = span.attributes().get("messaging.destination.name");
        if (destination == null)
            destination = span.attributes().get("messaging.destination");
        String targetName = destination != null ? destination : messagingSystem;
        return new ResolvedTarget(targetName, null, NodeType.QUEUE);
    }

    private ResolvedTarget resolveDatabase(ParsedSpan span) {
        String dbSystem = span.attributes().get("db.system");
        if (dbSystem == null)
            dbSystem = span.attributes().get("db.system.name");
        if (dbSystem == null)
            return null;

        String dbName = span.attributes().get("db.name");
        if (dbName == null)
            dbName = span.attributes().get("db.namespace");
        String targetName = dbName != null ? dbName : dbSystem;

        NodeType type = CACHE_SYSTEMS.contains(dbSystem.toLowerCase()) ? NodeType.CACHE : NodeType.DATABASE;
        return new ResolvedTarget(targetName, null, type);
    }

    private ResolvedTarget resolveService(ParsedSpan span) {
        // peer.service is the canonical way to identify the target service
        String peerService = span.attributes().get("peer.service");
        if (peerService != null) {
            if (isIgnoredServiceTarget(peerService)) {
                return null;
            }
            return new ResolvedTarget(peerService, null, NodeType.SERVICE);
        }

        // Try server address or net.peer.name
        String serverAddress = span.attributes().get("server.address");
        if (serverAddress == null)
            serverAddress = span.attributes().get("net.peer.name");
        if (serverAddress == null)
            serverAddress = span.attributes().get("http.host");

        if (serverAddress != null) {
            // Strip port if present
            String serviceName = serverAddress.contains(":") ? serverAddress.substring(0, serverAddress.indexOf(':'))
                    : serverAddress;
            if (isIgnoredServiceTarget(serviceName)) {
                return null;
            }
            return new ResolvedTarget(serviceName, null, NodeType.SERVICE);
        }

        // Try URL-based resolution
        String url = span.attributes().get("http.url");
        if (url == null)
            url = span.attributes().get("url.full");
        if (url != null) {
            String host = extractHostFromUrl(url);
            if (host != null) {
                if (isIgnoredServiceTarget(host)) {
                    return null;
                }
                return new ResolvedTarget(host, null, NodeType.INPUT);
            }
        }

        return null;
    }

    private boolean isIgnoredServiceCall(ParsedSpan span) {
        String peerService = span.attributes().get("peer.service");
        if (isIgnoredServiceTarget(peerService)) {
            return true;
        }

        String serverAddress = span.attributes().get("server.address");
        if (serverAddress == null)
            serverAddress = span.attributes().get("net.peer.name");
        if (serverAddress == null)
            serverAddress = span.attributes().get("http.host");
        if (serverAddress != null) {
            String serviceName = serverAddress.contains(":")
                    ? serverAddress.substring(0, serverAddress.indexOf(':'))
                    : serverAddress;
            if (isIgnoredServiceTarget(serviceName)) {
                return true;
            }
        }

        String url = span.attributes().get("http.url");
        if (url == null)
            url = span.attributes().get("url.full");
        if (url != null) {
            String host = extractHostFromUrl(url);
            if (isIgnoredServiceTarget(host)) {
                return true;
            }
        }

        return false;
    }

    private boolean isIgnoredServiceTarget(String serviceName) {
        return serviceName != null && IGNORED_SERVICE_TARGETS.contains(serviceName.toLowerCase());
    }

    private String extractHostFromUrl(String url) {
        try {
            // Simple host extraction: skip scheme, extract host before port/path
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0)
                return null;
            String rest = url.substring(schemeEnd + 3);
            int pathStart = rest.indexOf('/');
            String hostPort = pathStart >= 0 ? rest.substring(0, pathStart) : rest;
            int portStart = hostPort.indexOf(':');
            return portStart >= 0 ? hostPort.substring(0, portStart) : hostPort;
        } catch (Exception e) {
            return null;
        }
    }
}
