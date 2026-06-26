package com.kubevizor.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesPodWatcherTest {

    private final PodIpResolver podIpResolver = new PodIpResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KubernetesPodWatcher watcher = new KubernetesPodWatcher(podIpResolver, objectMapper, "default");

    @Test
    void processPodList_registersIpToAppLabel() throws Exception {
        String json = """
                {
                  "items": [{
                    "metadata": {
                      "name": "order-service-79bbf4f866-c6jvl",
                      "labels": {"app": "order-service"}
                    },
                    "status": {"podIP": "10.244.0.5"}
                  }]
                }
                """;

        watcher.processPodList(json);

        assertEquals("order-service", podIpResolver.resolve("10.244.0.5"));
    }

    @Test
    void processPodList_registersIpToK8sNameLabel_whenAppLabelAbsent() throws Exception {
        String json = """
                {
                  "items": [{
                    "metadata": {
                      "name": "ticket-service-abc123-xyz",
                      "labels": {"app.kubernetes.io/name": "ticket-service"}
                    },
                    "status": {"podIP": "10.244.0.6"}
                  }]
                }
                """;

        watcher.processPodList(json);

        assertEquals("ticket-service", podIpResolver.resolve("10.244.0.6"));
    }

    @Test
    void processPodList_skipsPodsWithoutIp() throws Exception {
        String json = """
                {
                  "items": [{
                    "metadata": {"name": "pending-pod", "labels": {"app": "auth-service"}},
                    "status": {}
                  }]
                }
                """;

        watcher.processPodList(json);

        assertEquals(0, podIpResolver.size());
    }

    @Test
    void processPodList_handlesMultiplePods() throws Exception {
        String json = """
                {
                  "items": [
                    {
                      "metadata": {"name": "auth-service-1", "labels": {"app": "auth-service"}},
                      "status": {"podIP": "10.244.0.10"}
                    },
                    {
                      "metadata": {"name": "k6-load-test", "labels": {"app": "k6"}},
                      "status": {"podIP": "10.244.0.11"}
                    }
                  ]
                }
                """;

        watcher.processPodList(json);

        assertEquals("auth-service", podIpResolver.resolve("10.244.0.10"));
        assertEquals("k6", podIpResolver.resolve("10.244.0.11"));
    }
}
