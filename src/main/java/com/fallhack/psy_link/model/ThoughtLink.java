package com.fallhack.psy_link.model;

import jakarta.persistence.*;

@Entity
public class ThoughtLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fromId;
    private Long toId;
    private Long typeId;

    /** Optional short reason (filled in by the AI for suggested links). */
    private String label;

    private boolean aiSuggested;

    public ThoughtLink() {}

    public ThoughtLink(Long fromId, Long toId, Long typeId, String label, boolean aiSuggested) {
        this.fromId = fromId;
        this.toId = toId;
        this.typeId = typeId;
        this.label = label;
        this.aiSuggested = aiSuggested;
    }

    public Long getId() { return id; }
    public Long getFromId() { return fromId; }
    public Long getToId() { return toId; }
    public Long getTypeId() { return typeId; }
    public void setTypeId(Long typeId) { this.typeId = typeId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isAiSuggested() { return aiSuggested; }
}
