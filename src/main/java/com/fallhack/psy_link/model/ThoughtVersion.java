package com.fallhack.psy_link.model;

import jakarta.persistence.*;

import java.time.Instant;

/** One snapshot in a thought's development history. Snapshots are only ever added, never edited. */
@Entity
public class ThoughtVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long thoughtId;

    private String title;

    @Column(length = 4000)
    private String text;

    private String category;

    /** Why this snapshot exists: "Created", "Edited", "Original", "Merged from ...". */
    private String note;

    private Instant createdAt;

    public ThoughtVersion() {}

    public ThoughtVersion(Long thoughtId, String title, String text, String category, String note) {
        this(thoughtId, title, text, category, note, Instant.now());
    }

    public ThoughtVersion(Long thoughtId, String title, String text, String category, String note, Instant createdAt) {
        this.thoughtId = thoughtId;
        this.title = title;
        this.text = text;
        this.category = category;
        this.note = note;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getThoughtId() { return thoughtId; }
    public String getTitle() { return title; }
    public String getText() { return text; }
    public String getCategory() { return category; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
