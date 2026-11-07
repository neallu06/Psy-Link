package com.fallhack.psy_space.model;

import jakarta.persistence.*;

@Entity
public class IdeaSpace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ownerId;

    private String name;

    @Column(length = 1000)
    private String description;

    public IdeaSpace() {}

    public IdeaSpace(Long ownerId, String name, String description) {
        this.ownerId = ownerId;
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}