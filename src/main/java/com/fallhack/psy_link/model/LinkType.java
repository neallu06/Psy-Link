package com.fallhack.psy_link.model;

import jakarta.persistence.*;

/** A connection type (e.g. "Supports"). Each user gets their own copy so colors/line style are customizable. */
@Entity
public class LinkType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ownerId;

    /** Stable key used by the AI: related, supports, contradicts, develops, part. */
    @Column(name = "type_key")
    private String key;

    private String name;

    @Column(length = 300)
    private String description;

    private String color;

    /** true = dotted line, false = solid line. */
    private boolean dotted;

    public LinkType() {}

    public LinkType(Long ownerId, String key, String name, String description, String color, boolean dotted) {
        this.ownerId = ownerId;
        this.key = key;
        this.name = name;
        this.description = description;
        this.color = color;
        this.dotted = dotted;
    }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public boolean isDotted() { return dotted; }
    public void setDotted(boolean dotted) { this.dotted = dotted; }
}