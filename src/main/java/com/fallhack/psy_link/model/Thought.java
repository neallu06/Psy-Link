package com.fallhack.psy_link.model;

import jakarta.persistence.*;

@Entity
public class Thought {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Renamable main topic shown on the box. */
    private String title;

    /** The user's own words, kept exactly as typed unless the user edits them. */
    @Column(length = 4000)
    private String text;

    private String category;

    /** Comma-separated tags. */
    private String tags;

    @Column(length = 1000)
    private String summary;

    private Long spaceId;

    private Double x;
    private Double y;

    public Thought() {}

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
}