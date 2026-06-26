package com.kubevizor.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface GraphSnapshotRepository extends JpaRepository<GraphSnapshotEntity, Long> {

    List<GraphSnapshotEntity> findByCapturedAtBetweenOrderByCapturedAtAsc(Instant from, Instant to);

    List<GraphSnapshotEntity> findByNamespaceAndCapturedAtBetweenOrderByCapturedAtAsc(String namespace, Instant from,
            Instant to);

    @Modifying
    @Query("DELETE FROM GraphSnapshotEntity e WHERE e.capturedAt < :cutoff")
    int deleteByCapturedAtBefore(@Param("cutoff") Instant cutoff);
}
