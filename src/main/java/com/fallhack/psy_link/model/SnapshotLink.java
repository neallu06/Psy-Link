package com.fallhack.psy_link.model;

import jakarta.persistence.*;

/** A connection as it was when a snapshot was taken. Ends refer to SnapshotThought.origId. */
@Entity
public class SnapshotLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long snapshotId;
    private Long fromOrigId;
    private Long toOrigId;
    private Long typeId;
    private String label;
    private boolean aiSuggested;

    public SnapshotLink() {}

    public SnapshotLink(Long snapshotId, ThoughtLink l) {
        this.snapshotId = snapshotId;
        this.fromOrigId = l.getFromId();
        this.toOrigId = l.getToId();
        this.typeId = l.getTypeId();
        this.label = l.getLabel();
        this.aiSuggested = l.isAiSuggested();
    }

    public Long getId() { return id; }
    public Long getSnapshotId() { return snapshotId; }
    public Long getFromOrigId() { return fromOrigId; }
    public Long getToOrigId() { return toOrigId; }
    public Long getTypeId() { return typeId; }
    public String getLabel() { return label; }
    public boolean isAiSuggested() { return aiSuggested; }
}
