package com.kubevizor.support;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger documentation configuration.
 * Consumed by the frontend agent at /v3/api-docs or /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kubevizorOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kubeflow Backend API")
                        .version("0.1.0")
                        .description(
                                """
                                        Topology-processing backend for Kubernetes workload visualization.

                                        **Responsibilities**
                                        - Accepts OpenTelemetry spans (`/v1/traces`) and Beyla/kubeletstats metrics (`/v1/metrics`).
                                        - Derives a live service topology graph: nodes (services, databases, caches, queues) and
                                          directed edges with per-edge metrics (RPS, latency, error rate).
                                        - Computes a `loadLevel` on each edge from the CPU / memory utilization of the target pod reported by the kubelestats receiver.
                                        - Streams graph updates to the frontend via SSE (`GET /api/graph/stream`).
                                        - Persists rolling 24-hour snapshot history queryable via `GET /api/graph/history`.

                                        **Frontend integration**
                                        - Poll or stream `GET /api/graph` for the current topology.
                                        - Subscribe to `GET /api/graph/stream` (SSE, event name `graph-update`) for live push.
                                        - Each event/response carries a `GraphSnapshot[]` — one entry per Kubernetes namespace.
                                        """)
                        .contact(new Contact().name("kubevizor.backend")))
                .addServersItem(new Server().url("http://localhost:8080").description("Local development"));
    }
}
