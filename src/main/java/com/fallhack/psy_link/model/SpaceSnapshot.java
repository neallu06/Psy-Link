package com.fallhack.psy_link.model;

import jakarta.persistence.*;

import java.time.Instant;

/** A saved copy of one idea space's whole graph. The thoughts and links live in SnapshotThought / SnapshotLink. */
@Entity
public class SpaceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long spaceId;
    private String label;
    private Instant createdAt = Instant.now();
    private int thoughtCount;
    private int linkCount;

    public SpaceSnapshot() {}

    public SpaceSnapshot(Long spaceId, String label, int thoughtCount, int linkCount) {
        this.spaceId = spaceId;
        this.label = label;
        this.thoughtCount = thoughtCount;
        this.linkCount = linkCount;
    }

    public Long getId() { return id; }
    public Long getSpaceId() { return spaceId; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }
    public int getThoughtCount() { return thoughtCount; }
    public int getLinkCount() { return linkCount; }
}
