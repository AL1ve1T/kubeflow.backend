package com.kubevizor.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA entity for persisting graph snapshots.
 * Stores the serialized JSON of a GraphSnapshot with a timestamp.
 */
@Entity
@Table(name = "graph_snapshots")
public class GraphSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant capturedAt;

    @Column(nullable = false)
    private String namespace;

    // TEXT in PostgreSQL; H2 (PostgreSQL mode) also stores this as text/varchar.
    // No @Lob — that would map to CLOB which conflicts with TEXT during schema
    // validation.
    @Column(columnDefinition = "TEXT", nullable = false)
    private String snapshotJson;

    protected GraphSnapshotEntity() {
    }

    public GraphSnapshotEntity(Instant capturedAt, String namespace, String snapshotJson) {
        this.capturedAt = capturedAt;
        this.namespace = namespace;
        this.snapshotJson = snapshotJson;
    }

    public Long getId() {
        return id;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }
}
