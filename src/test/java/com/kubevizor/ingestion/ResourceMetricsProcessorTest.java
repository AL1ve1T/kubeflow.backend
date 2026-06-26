package com.kubevizor.ingestion;

import com.kubevizor.aggregation.GraphStateManager;
import com.kubevizor.support.KubevizorProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceMetricsProcessorTest {

        private final GraphStateManager graphStateManager = new GraphStateManager(new KubevizorProperties());
        private final ResourceMetricsProcessor processor = new ResourceMetricsProcessor(graphStateManager,
                        new KubevizorProperties());

        @Test
        void processMetrics_withContainerCpuUtilization_updatesNode() {
                var payload = buildPayload("container.cpu.utilization", "auth-service", "default", 0.65);

                processor.processMetrics(payload);

                var node = graphStateManager.getNodes().get("auth-service");
                assertNotNull(node);
                assertEquals(0.65, node.getCpuUtilization(), 0.001);
        }

        @Test
        void processMetrics_withPodCpuUtilization_updatesNode() {
                // k8s.pod.cpu.utilization is the metric name emitted by pod-level kubeletstats
                var payload = buildPayload("k8s.pod.cpu.utilization", "order-service", "demo", 0.42);

                processor.processMetrics(payload);

                var node = graphStateManager.getNodes().get("order-service");
                assertNotNull(node);
                assertEquals(0.42, node.getCpuUtilization(), 0.001);
        }

        @Test
        void processMetrics_withContainerMemoryLimitUtilization_updatesNode() {
                var payload = buildPayload("k8s.container.memory_limit_utilization", "ticket-service", "demo", 0.78);

                processor.processMetrics(payload);

                var node = graphStateManager.getNodes().get("ticket-service");
                assertNotNull(node);
                assertEquals(0.78, node.getMemoryUtilization(), 0.001);
        }

        @Test
        void processMetrics_withDeploymentNameAttribute_resolvesWorkload() {
                // kubeletstats sets k8s.deployment.name which should be used directly
                Map<String, Object> payload = Map.of("resourceMetrics", List.of(
                                Map.of(
                                                "resource", Map.of("attributes", List.of(
                                                                Map.of("key", "k8s.deployment.name", "value",
                                                                                Map.of("stringValue", "auth-service")),
                                                                Map.of("key", "k8s.namespace.name", "value",
                                                                                Map.of("stringValue", "default")))),
                                                "scopeMetrics", List.of(Map.of(
                                                                "metrics", List.of(
                                                                                Map.of("name", "k8s.pod.cpu.utilization",
                                                                                                "gauge",
                                                                                                Map.of("dataPoints",
                                                                                                                List.of(Map.of("asDouble",
                                                                                                                                0.55))))))))));

                processor.processMetrics(payload);

                assertEquals(0.55, graphStateManager.getNodes().get("auth-service").getCpuUtilization(), 0.001);
        }

        @Test
        void processMetrics_withContainerMemoryWorkingSetBytes_convertsToRatio() {
                // 268435456 bytes = 256 MiB; default limit = 512 MiB → ratio = 0.5
                long workingSetBytes = 256L * 1024 * 1024;
                var payload = buildPayload("container.memory.working_set", "auth-service", "default", workingSetBytes);

                processor.processMetrics(payload);

                var node = graphStateManager.getNodes().get("auth-service");
                assertNotNull(node);
                assertEquals(0.5, node.getMemoryUtilization(), 0.001);
        }

        @Test
        void processMetrics_withEmptyPayload_doesNotThrow() {
                assertDoesNotThrow(() -> processor.processMetrics(Map.of()));
        }

        @Test
        void processMetrics_withMultipleReplicas_tracksEachPodAndRollsUpMax() {
                // Two pods of the same deployment report different CPU in one payload.
                Map<String, Object> payload = Map.of("resourceMetrics", List.of(
                                replicaResource("order-service-abc12-rep01", "demo", "k8s.pod.cpu.utilization", 0.20),
                                replicaResource("order-service-abc12-rep02", "demo", "k8s.pod.cpu.utilization", 0.80)));

                processor.processMetrics(payload);

                var node = graphStateManager.getNodes().get("order-service");
                assertNotNull(node);
                // Both replicas are tracked individually...
                assertEquals(2, node.getPods().size());
                assertEquals(0.20, node.getPods().get("order-service-abc12-rep01").getCpuUtilization(), 0.001);
                assertEquals(0.80, node.getPods().get("order-service-abc12-rep02").getCpuUtilization(), 0.001);
                // ...and the workload roll-up surfaces the hottest replica (max), not an
                // average.
                assertEquals(0.80, node.getCpuUtilization(), 0.001);
        }

        private Map<String, Object> replicaResource(String podName, String namespace, String metricName, double value) {
                return Map.of(
                                "resource", Map.of("attributes", List.of(
                                                Map.of("key", "k8s.pod.name", "value", Map.of("stringValue", podName)),
                                                Map.of("key", "k8s.namespace.name", "value",
                                                                Map.of("stringValue", namespace)))),
                                "scopeMetrics", List.of(Map.of(
                                                "metrics", List.of(
                                                                Map.of("name", metricName,
                                                                                "gauge", Map.of("dataPoints",
                                                                                                List.of(Map.of("asDouble",
                                                                                                                value))))))));
        }

        // Builds a minimal OTLP metrics payload with a gauge metric, workload
        // identified by pod name
        private Map<String, Object> buildPayload(String metricName, String podName, String namespace, double value) {
                return Map.of("resourceMetrics", List.of(
                                Map.of(
                                                "resource", Map.of("attributes", List.of(
                                                                Map.of("key", "k8s.pod.name", "value",
                                                                                Map.of("stringValue", podName
                                                                                                + "-abc12-xyz99")),
                                                                Map.of("key", "k8s.namespace.name", "value",
                                                                                Map.of("stringValue", namespace)))),
                                                "scopeMetrics", List.of(Map.of(
                                                                "metrics", List.of(
                                                                                Map.of("name", metricName,
                                                                                                "gauge",
                                                                                                Map.of("dataPoints",
                                                                                                                List.of(Map.of("asDouble",
                                                                                                                                value))))))))));
        }
}
