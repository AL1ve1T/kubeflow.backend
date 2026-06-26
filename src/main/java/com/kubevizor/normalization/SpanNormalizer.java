package com.kubevizor.normalization;

import com.kubevizor.model.InteractionEvent;
import com.kubevizor.model.NodeType;
import com.kubevizor.parsing.ParsedSpan;
import com.kubevizor.topology.PodIpResolver;
import com.kubevizor.topology.TopologyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converts ParsedSpan objects into normalized InteractionEvent objects.
 * Client spans produce edges from source to resolved target.
 * Server spans produce edges from inferred caller to the receiving service.
 */
@Component
public class SpanNormalizer {

    private static final Logger log = LoggerFactory.getLogger(SpanNormalizer.class);

    private final TopologyResolver topologyResolver;
    private final PodIpResolver podIpResolver;

    public SpanNormalizer(TopologyResolver topologyResolver, PodIpResolver podIpResolver) {
        this.topologyResolver = topologyResolver;
        this.podIpResolver = podIpResolver;
    }

    public InteractionEvent normalize(ParsedSpan span) {
        if (span.isClientSpan()) {
            return normalizeClientSpan(span);
        }
        if (span.isServerSpan()) {
            return normalizeServerSpan(span);
        }
        return null;
    }

    private InteractionEvent normalizeClientSpan(ParsedSpan span) {
        TopologyResolver.ResolvedTarget target = topologyResolver.resolveTarget(span);
        if (target == null) {
            log.debug("Could not resolve target for client span: traceId={}, spanId={}", span.traceId(), span.spanId());
            return null;
        }

        return new InteractionEvent(
                span.traceId(),
                span.spanId(),
                span.serviceName(),
                span.serviceNamespace(),
                target.serviceName(),
                target.namespace(),
                target.nodeType(),
                resolveProtocol(span),
                span.durationMs(),
                span.isError(),
                span.startInstant());
    }

    private InteractionEvent normalizeServerSpan(ParsedSpan span) {
        String caller = resolveCallerService(span);
        if (caller == null) {
            // Skip infrastructure probes (Kubernetes liveness/readiness checks etc.)
            if (isInfraProbe(span)) {
                return null;
            }
            // Only fall back to "external" for HTTP/gRPC server spans.
            // Non-HTTP spans (postgres connections, raw TCP) have an unknowable caller
            // and are already covered by CLIENT spans from the OTel-instrumented caller.
            if (!isHttpServerSpan(span)) {
                return null;
            }
            caller = "external";
        }

        return new InteractionEvent(
                span.traceId(),
                span.spanId(),
                caller,
                null,
                span.serviceName(),
                span.serviceNamespace(),
                NodeType.SERVICE,
                resolveProtocol(span),
                span.durationMs(),
                span.isError(),
                span.startInstant());
    }

    private boolean isHttpServerSpan(ParsedSpan span) {
        // HTTP spans carry a method attribute — check all known semconv variants:
        // - "http.request.method" (OTel HTTP semconv v1.20+ / Beyla latest)
        // - "http.method" (legacy OTel semconv)
        // - "method" (old Beyla eBPF shorthand)
        // - "rpc.method" (gRPC spans)
        return span.attributes().containsKey("http.request.method")
                || span.attributes().containsKey("method")
                || span.attributes().containsKey("http.method")
                || span.attributes().containsKey("rpc.method");
    }

    private boolean isInfraProbe(ParsedSpan span) {
        // Check the uri attribute (Spring Boot sets this to the path template)
        String uri = span.attributes().get("uri");
        if (uri == null)
            uri = span.attributes().get("http.target");
        if (uri == null)
            uri = span.attributes().get("url.path");
        if (uri != null) {
            String lower = uri.toLowerCase();
            if (lower.startsWith("/actuator") || lower.startsWith("/health")
                    || lower.startsWith("/readyz") || lower.startsWith("/livez")) {
                return true;
            }
        }
        // Fall back to span name (e.g. "http get /actuator/health")
        String spanName = span.spanName();
        return spanName != null && spanName.toLowerCase().contains("/actuator");
    }

    private String resolveCallerService(ParsedSpan span) {
        // Check for explicit caller service attributes
        String peerService = span.attributes().get("peer.service");
        if (peerService != null)
            return peerService;

        String clientName = span.attributes().get("client.service.name");
        if (clientName != null)
            return clientName;

        // Resolve caller from client.address.
        // Beyla can set this to either a raw pod IP or an already-resolved workload
        // name,
        // depending on whether Kubernetes metadata was available at capture time.
        String clientAddress = span.attributes().get("client.address");
        if (clientAddress != null) {
            if (looksLikeIpAddress(clientAddress)) {
                // Strip port suffix if present (e.g. "10.244.0.5:12345" -> "10.244.0.5").
                String ip = clientAddress.contains(":") ? clientAddress.substring(0, clientAddress.lastIndexOf(':'))
                        : clientAddress;
                String resolved = podIpResolver.resolve(ip);
                if (resolved != null)
                    return resolved;
                log.debug("Could not resolve client.address={} to a service name", clientAddress);
            } else {
                // Already a workload/service name — use directly.
                return clientAddress;
            }
        }

        return null;
    }

    private String resolveProtocol(ParsedSpan span) {
        String dbSystem = span.attributes().get("db.system");
        if (dbSystem == null)
            dbSystem = span.attributes().get("db.system.name");
        if (dbSystem != null)
            return dbSystem;

        String rpcSystem = span.attributes().get("rpc.system");
        if (rpcSystem != null)
            return rpcSystem;

        String httpMethod = span.attributes().get("http.method");
        if (httpMethod == null)
            httpMethod = span.attributes().get("http.request.method");
        if (httpMethod != null)
            return "HTTP";

        return "unknown";
    }

    private static final java.util.regex.Pattern IP_PATTERN = java.util.regex.Pattern
            .compile("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$|^\\[?[0-9a-fA-F:]+\\]?(:\\d+)?$");

    private boolean looksLikeIpAddress(String value) {
        return IP_PATTERN.matcher(value).matches();
    }
}
