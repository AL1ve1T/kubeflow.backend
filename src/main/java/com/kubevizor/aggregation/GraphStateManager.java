package com.kubevizor.aggregation;

import com.kubevizor.model.Edge;
import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.InteractionEvent;
import com.kubevizor.model.LoadLevel;
import com.kubevizor.model.Node;
import com.kubevizor.model.NodeType;
import com.kubevizor.model.PodInstance;
import com.kubevizor.model.PodPhase;
import com.kubevizor.support.KubevizorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Maintains the in-memory graph state: nodes and edges with rolling metrics.
 * Thread-safe for concurrent span processing.
 * Mutation methods set a dirty flag so that the publisher can detect changes.
 */
@Component
public class GraphStateManager {

    private static final Logger log = LoggerFactory.getLogger(GraphStateManager.class);

    private final ConcurrentMap<String, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Edge> edges = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final KubevizorProperties properties;

    public GraphStateManager(KubevizorProperties properties) {
        this.properties = properties;
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public boolean resetDirty() {
        return dirty.getAndSet(false);
    }

    public void registerNode(String serviceName, String namespace) {
        if (serviceName == null || serviceName.isBlank()) {
            return;
        }
        Node node = nodes.computeIfAbsent(serviceName,
                id -> {
                    dirty.set(true);
                    return new Node(id, id, com.kubevizor.model.NodeType.SERVICE, namespace);
                });
        node.touch();
    }

    public void registerEdge(InteractionEvent event) {
        // Register both nodes
        Node sourceNode = nodes.computeIfAbsent(event.sourceService(),
                id -> {
                    dirty.set(true);
                    NodeType srcType = isSyntheticInputNode(id) ? NodeType.INPUT : NodeType.SERVICE;
                    return new Node(id, id, srcType, event.sourceNamespace());
                });
        sourceNode.touch();
        if (sourceNode.getNamespace() == null && event.sourceNamespace() != null) {
            sourceNode.setNamespace(event.sourceNamespace());
            dirty.set(true);
        }
        Node targetNode = nodes.computeIfAbsent(event.targetService(),
                id -> {
                    dirty.set(true);
                    return new Node(id, id, event.targetType(), event.targetNamespace());
                });
        targetNode.touch();
        if (targetNode.getNamespace() == null && event.targetNamespace() != null) {
            targetNode.setNamespace(event.targetNamespace());
            dirty.set(true);
        }
        upgradeNodeTypeIfMoreSpecific(targetNode, event.targetType());

        // Create skeleton edge if it doesn't exist yet (no metrics recorded)
        String edgeId = event.sourceService() + "->" + event.targetService();
        edges.computeIfAbsent(edgeId,
                id -> {
                    dirty.set(true);
                    return new Edge(event.sourceService(), event.targetService(), event.protocol());
                });

        log.debug("Registered edge skeleton: {} -> {}", event.sourceService(), event.targetService());
    }

    public void recordTraffic(InteractionEvent event) {
        String edgeId = event.sourceService() + "->" + event.targetService();
        Edge edge = edges.get(edgeId);
        if (edge != null) {
            edge.recordRequest(event.latencyMs(), event.isError());
            dirty.set(true);
            log.debug("Recorded traffic: {} -> {} ({}ms, error={})",
                    event.sourceService(), event.targetService(), event.latencyMs(), event.isError());
        }
    }

    public void registerNetworkFlowEdge(String sourceId, String sourceNamespace,
            String targetId, String targetNamespace,
            String protocol, NodeType targetType, NodeType sourceType) {
        Node srcNode = nodes.computeIfAbsent(sourceId,
                id -> {
                    dirty.set(true);
                    return new Node(id, id, sourceType, sourceNamespace);
                });
        srcNode.touch();
        if (srcNode.getNamespace() == null && sourceNamespace != null) {
            srcNode.setNamespace(sourceNamespace);
            dirty.set(true);
        }
        Node tgtNode = nodes.computeIfAbsent(targetId,
                id -> {
                    dirty.set(true);
                    return new Node(id, id, targetType, targetNamespace);
                });
        tgtNode.touch();
        if (tgtNode.getNamespace() == null && targetNamespace != null) {
            tgtNode.setNamespace(targetNamespace);
            dirty.set(true);
        }
        upgradeNodeTypeIfMoreSpecific(tgtNode, targetType);

        String edgeId = sourceId + "->" + targetId;
        Edge edge = edges.computeIfAbsent(edgeId,
                id -> {
                    dirty.set(true);
                    return new Edge(sourceId, targetId, protocol);
                });
        edge.touch();
        dirty.set(true);
        log.debug("Registered network flow edge: {} -> {}", sourceId, targetId);
    }

    private void upgradeNodeTypeIfMoreSpecific(Node node, NodeType candidateType) {
        if (node.getType() == com.kubevizor.model.NodeType.SERVICE
                && candidateType != com.kubevizor.model.NodeType.SERVICE
                && candidateType != com.kubevizor.model.NodeType.INPUT) {
            node.setType(candidateType);
            dirty.set(true);
        }
    }

    public void applyEvent(InteractionEvent event) {
        registerEdge(event);
        recordTraffic(event);
    }

    public List<GraphSnapshot> buildSnapshots() {
        // Group edges by target node's namespace
        Map<String, List<Edge>> edgesByNamespace = new HashMap<>();
        for (Edge e : edges.values()) {
            Node targetNode = nodes.get(e.getTargetNodeId());
            String ns = targetNode != null ? targetNode.getNamespace() : null;
            if (ns == null) {
                ns = "unknown";
            }
            edgesByNamespace.computeIfAbsent(ns, k -> new ArrayList<>()).add(e);
        }

        // Collect all namespaces (from nodes that have one)
        Set<String> allNamespaces = nodes.values().stream()
                .map(Node::getNamespace)
                .filter(ns -> ns != null && !ns.isEmpty())
                .collect(Collectors.toSet());
        // Ensure edge-derived namespaces are included
        allNamespaces.addAll(edgesByNamespace.keySet());

        Instant now = Instant.now();
        List<GraphSnapshot> snapshots = new ArrayList<>();

        for (String ns : allNamespaces) {
            List<Edge> nsEdges = edgesByNamespace.getOrDefault(ns, List.of());

            // Collect all node IDs referenced by this namespace's edges
            Set<String> referencedNodeIds = nsEdges.stream()
                    .flatMap(e -> java.util.stream.Stream.of(e.getSourceNodeId(), e.getTargetNodeId()))
                    .collect(Collectors.toSet());

            // Also include nodes that belong to this namespace but have no edges yet
            for (Node n : nodes.values()) {
                if (ns.equals(n.getNamespace())) {
                    referencedNodeIds.add(n.getId());
                }
            }

            List<GraphSnapshot.NodeDto> nodeDtos = referencedNodeIds.stream()
                    .map(nodes::get)
                    .filter(n -> n != null)
                    .map(n -> new GraphSnapshot.NodeDto(
                            n.getId(), n.getName(), n.getType(),
                            n.getPodPhase(), n.getRestartCount(), n.getPodCount(),
                            n.getLastRestartAt(), n.getLastRestartReason(),
                            n.getLastSeenAt(), buildPodDtos(n, now)))
                    .toList();

            List<GraphSnapshot.EdgeDto> edgeDtos = nsEdges.stream()
                    .map(e -> {
                        Node target = nodes.get(e.getTargetNodeId());
                        return new GraphSnapshot.EdgeDto(
                                e.getId(), e.getSourceNodeId(), e.getTargetNodeId(),
                                e.getProtocol(), round2(e.getRequestsPerSecond(now)),
                                round2(e.getAverageLatencyMs(now)), round2(e.getMaxLatencyMs(now)),
                                e.getErrorCount(), round2(e.getErrorRate(now)),
                                classifyLoad(target), e.getLastSeenAt());
                    })
                    .toList();

            snapshots.add(new GraphSnapshot(ns, nodeDtos, edgeDtos, now));
        }

        return snapshots;
    }

    public GraphSnapshot buildSnapshot(String namespace) {
        return buildSnapshots().stream()
                .filter(s -> s.namespace().equals(namespace))
                .findFirst()
                .orElse(new GraphSnapshot(namespace, List.of(), List.of(), Instant.now()));
    }

    public void removeNode(String nodeId) {
        if (nodes.remove(nodeId) != null) {
            dirty.set(true);
        }
        if (edges.entrySet().removeIf(entry -> entry.getValue().getSourceNodeId().equals(nodeId) ||
                entry.getValue().getTargetNodeId().equals(nodeId))) {
            dirty.set(true);
        }
    }

    public void removeEdge(String edgeId) {
        if (edges.remove(edgeId) != null) {
            dirty.set(true);
        }
    }

    public void updateNodeCpuUtilization(String nodeId, String namespace, String podName, double cpuUtil) {
        Node node = nodes.computeIfAbsent(nodeId,
                id -> new Node(id, id, NodeType.SERVICE, namespace));
        node.pod(podName).setCpuUtilization(cpuUtil);
        node.setCpuUtilization(maxPodCpu(node));
        dirty.set(true);
    }

    public void updateNodeMemoryUtilization(String nodeId, String namespace, String podName, double memUtil) {
        Node node = nodes.computeIfAbsent(nodeId,
                id -> new Node(id, id, NodeType.SERVICE, namespace));
        node.pod(podName).setMemoryUtilization(memUtil);
        node.setMemoryUtilization(maxPodMemory(node));
        dirty.set(true);
    }

    public void updateNodePodStatus(String workloadName, String namespace, String podName, PodPhase phase,
            int restartCount, java.time.Instant lastRestartAt, String lastRestartReason) {
        Node node = nodes.computeIfAbsent(workloadName,
                id -> {
                    dirty.set(true);
                    return new Node(id, id, NodeType.SERVICE, namespace);
                });
        PodInstance pod = node.pod(podName);
        boolean changed = pod.getPodPhase() != phase || pod.getRestartCount() != restartCount
                || !java.util.Objects.equals(pod.getLastRestartAt(), lastRestartAt);
        pod.setStatus(phase, restartCount, lastRestartAt, lastRestartReason);
        if (changed || node.getPodCount() != node.getPods().size()) {
            recomputeStatusRollup(node);
            dirty.set(true);
        }
    }

    /**
     * Removes pod replicas that have not reported any signal since {@code cutoff}
     * and refreshes the affected workload roll-ups. Pods are ephemeral (names
     * change on scale or restart), so a replica that stops reporting is dropped.
     */
    public void pruneStalePods(java.time.Instant cutoff) {
        for (Node node : nodes.values()) {
            boolean removed = node.getPods().values()
                    .removeIf(p -> p.getLastSeenAt().isBefore(cutoff));
            if (removed) {
                node.setCpuUtilization(maxPodCpu(node));
                node.setMemoryUtilization(maxPodMemory(node));
                recomputeStatusRollup(node);
                dirty.set(true);
            }
        }
    }

    private double maxPodCpu(Node node) {
        return node.getPods().values().stream()
                .mapToDouble(PodInstance::getCpuUtilization).max().orElse(0.0);
    }

    private double maxPodMemory(Node node) {
        return node.getPods().values().stream()
                .mapToDouble(PodInstance::getMemoryUtilization).max().orElse(0.0);
    }

    /**
     * Recomputes the workload-level health roll-up from its pods: worst-case
     * phase, the highest single-pod restart count, the most recent restart, and
     * the live replica count.
     */
    private void recomputeStatusRollup(Node node) {
        PodPhase worst = PodPhase.UNKNOWN;
        int restarts = 0;
        java.time.Instant lastRestartAt = null;
        String lastRestartReason = null;
        for (PodInstance pod : node.getPods().values()) {
            if (pod.getPodPhase().isWorseThan(worst)) {
                worst = pod.getPodPhase();
            }
            restarts = Math.max(restarts, pod.getRestartCount());
            java.time.Instant podRestartAt = pod.getLastRestartAt();
            if (podRestartAt != null
                    && (lastRestartAt == null || podRestartAt.isAfter(lastRestartAt))) {
                lastRestartAt = podRestartAt;
                lastRestartReason = pod.getLastRestartReason();
            }
        }
        node.setPodPhase(worst);
        node.setRestartCount(restarts);
        node.setPodCount(node.getPods().size());
        node.setLastRestartAt(lastRestartAt);
        node.setLastRestartReason(lastRestartReason);
    }

    private List<GraphSnapshot.PodDto> buildPodDtos(Node node, Instant now) {
        return node.getPods().values().stream()
                .sorted(java.util.Comparator.comparing(PodInstance::getPodName))
                .map(p -> new GraphSnapshot.PodDto(
                        p.getPodName(),
                        round2(isResourceMetricFresh(p.getLastCpuUpdatedAt(), now) ? p.getCpuUtilization() : 0.0),
                        round2(isResourceMetricFresh(p.getLastMemoryUpdatedAt(), now) ? p.getMemoryUtilization() : 0.0),
                        p.getPodPhase(), p.getRestartCount(),
                        p.getLastRestartAt(), p.getLastRestartReason(),
                        p.getLastSeenAt()))
                .toList();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private LoadLevel classifyLoad(Node node) {
        if (node == null)
            return LoadLevel.NORMAL;
        Instant now = Instant.now();
        double cpu = effectiveCpuUtilization(node, now);
        double mem = effectiveMemoryUtilization(node, now);
        if (cpu >= properties.getCpuCriticalThreshold() || mem >= properties.getMemCriticalThreshold())
            return LoadLevel.CRITICAL;
        if (cpu >= properties.getCpuHighThreshold() || mem >= properties.getMemHighThreshold())
            return LoadLevel.HIGH;
        if (cpu >= properties.getCpuElevatedThreshold() || mem >= properties.getMemElevatedThreshold())
            return LoadLevel.ELEVATED;
        return LoadLevel.NORMAL;
    }

    private double effectiveCpuUtilization(Node node, Instant now) {
        return isResourceMetricFresh(node.getLastCpuUpdatedAt(), now) ? node.getCpuUtilization() : 0.0;
    }

    private double effectiveMemoryUtilization(Node node, Instant now) {
        return isResourceMetricFresh(node.getLastMemoryUpdatedAt(), now) ? node.getMemoryUtilization() : 0.0;
    }

    private boolean isResourceMetricFresh(Instant updatedAt, Instant now) {
        if (updatedAt == null) {
            return false;
        }
        Duration age = Duration.between(updatedAt, now);
        return age.compareTo(Duration.ofSeconds(properties.getResourceMetricStaleSeconds())) <= 0;
    }

    public ConcurrentMap<String, Node> getNodes() {
        return nodes;
    }

    public ConcurrentMap<String, Edge> getEdges() {
        return edges;
    }

    private static boolean isSyntheticInputNode(String nodeId) {
        return "external".equals(nodeId) || "internal".equals(nodeId);
    }
}
