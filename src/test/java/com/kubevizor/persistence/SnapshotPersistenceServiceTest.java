package com.kubevizor.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kubevizor.model.GraphSnapshot;
import com.kubevizor.model.NodeType;
import com.kubevizor.support.KubevizorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({ SnapshotPersistenceService.class, KubevizorProperties.class,
        SnapshotPersistenceServiceTest.Config.class })
class SnapshotPersistenceServiceTest {

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            return mapper;
        }
    }

    @Autowired
    private SnapshotPersistenceService service;

    @Autowired
    private GraphSnapshotRepository repository;

    @Test
    void save_persistsSnapshotToDatabase() {
        GraphSnapshot snapshot = new GraphSnapshot(
                "default",
                List.of(new GraphSnapshot.NodeDto("svc-a", "svc-a", NodeType.SERVICE,
                        com.kubevizor.model.PodPhase.UNKNOWN, 0, 0, null, null, Instant.now(), List.of())),
                List.of(),
                Instant.now());

        service.save(snapshot);

        assertEquals(1, repository.count());
    }

    @Test
    void getHistory_returnsSnapshotsInTimeRange() {
        Instant now = Instant.now();

        service.save(new GraphSnapshot("default", List.of(), List.of(), now.minus(Duration.ofMinutes(30))));
        service.save(new GraphSnapshot("default", List.of(), List.of(), now.minus(Duration.ofMinutes(10))));
        service.save(new GraphSnapshot("default", List.of(), List.of(), now.minus(Duration.ofHours(2))));

        List<GraphSnapshot> history = service.getHistory(now.minus(Duration.ofHours(1)), now);

        assertEquals(2, history.size());
    }

    @Test
    void purgeOlderThan_deletesExpiredSnapshots() {
        Instant now = Instant.now();

        // Save with explicit timestamps via repository directly
        repository.save(new GraphSnapshotEntity(now.minus(Duration.ofHours(25)), "default", "{}"));
        repository.save(new GraphSnapshotEntity(now.minus(Duration.ofHours(23)), "default", "{}"));
        repository.save(new GraphSnapshotEntity(now, "default", "{}"));

        int deleted = service.purgeOlderThan(Duration.ofHours(24));

        assertEquals(1, deleted);
        assertEquals(2, repository.count());
    }
}
