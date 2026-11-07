package com.fallhack.psy_link.model;

import jakarta.persistence.*;

/** A thought as it was when a snapshot was taken. origId is the thought's id at that moment. */
@Entity
public class SnapshotThought {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long snapshotId;
    private Long origId;
    private String title;

    @Column(length = 4000)
    private String text;

    private String category;
    private String tags;

    @Column(length = 1000)
    private String summary;

    private Double x;
    private Double y;
    private Boolean pinned = false;

    public SnapshotThought() {}

    public SnapshotThought(Long snapshotId, Thought t) {
        this.snapshotId = snapshotId;
        this.origId = t.getId();
        this.title = t.getTitle();
        this.text = t.getText();
        this.category = t.getCategory();
        this.tags = t.getTags();
        this.summary = t.getSummary();
        this.x = t.getX();
        this.y = t.getY();
        this.pinned = t.isPinned();
    }

    public Long getId() { return id; }
    public Long getSnapshotId() { return snapshotId; }
    public Long getOrigId() { return origId; }
    public String getTitle() { return title; }
    public String getText() { return text; }
    public String getCategory() { return category; }
    public String getTags() { return tags; }
    public String getSummary() { return summary; }
    public Double getX() { return x; }
    public Double getY() { return y; }
    public boolean isPinned() { return Boolean.TRUE.equals(pinned); }
}
