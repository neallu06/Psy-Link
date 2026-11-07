package com.fallhack.psy_link.service;

import com.fallhack.psy_link.model.IdeaSpace;
import com.fallhack.psy_link.model.Thought;
import com.fallhack.psy_link.model.ThoughtLink;
import com.fallhack.psy_link.model.ThoughtVersion;
import com.fallhack.psy_link.repo.IdeaSpaceRepository;
import com.fallhack.psy_link.repo.ThoughtLinkRepository;
import com.fallhack.psy_link.repo.ThoughtRepository;
import com.fallhack.psy_link.repo.ThoughtVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SpaceService {

    public record MergeResult(IdeaSpace space, int thoughtCount) {}

    private final IdeaSpaceRepository spaces;
    private final ThoughtRepository thoughts;
    private final ThoughtLinkRepository links;
    private final ThoughtVersionRepository versions;

    public SpaceService(IdeaSpaceRepository spaces, ThoughtRepository thoughts,
                        ThoughtLinkRepository links, ThoughtVersionRepository versions) {
        this.spaces = spaces;
        this.thoughts = thoughts;
        this.links = links;
        this.versions = versions;
    }

    /**
     * Copies both spaces into a brand-new space. The originals are never touched.
     * Each copied thought keeps its full development history, plus a "Merged from ..." entry.
     * Positions are cleared so the new space gets a fresh layout.
     */
    @Transactional
    public MergeResult merge(Long ownerId, IdeaSpace a, IdeaSpace b, String name) {
        IdeaSpace merged = spaces.save(new IdeaSpace(ownerId, name,
                "Merged from " + a.getName() + " and " + b.getName()));

        Map<Long, Long> idMap = new HashMap<>();
        for (IdeaSpace source : List.of(a, b)) {
            for (Thought t : thoughts.findBySpaceId(source.getId())) {
                Thought c = new Thought();
                c.setTitle(t.getTitle());
                c.setText(t.getText());
                c.setCategory(t.getCategory());
                c.setTags(t.getTags());
                c.setSummary(t.getSummary());
                c.setSpaceId(merged.getId());
                c = thoughts.save(c);
                idMap.put(t.getId(), c.getId());

                for (ThoughtVersion v : versions.findByThoughtIdOrderByIdAsc(t.getId())) {
                    versions.save(new ThoughtVersion(c.getId(), v.getTitle(), v.getText(),
                            v.getCategory(), v.getNote(), v.getCreatedAt()));
                }
                versions.save(new ThoughtVersion(c.getId(), c.getTitle(), c.getText(), c.getCategory(),
                        "Merged from " + source.getName()));
            }
        }

        for (ThoughtLink l : links.findByFromIdInAndToIdIn(idMap.keySet(), idMap.keySet())) {
            Long from = idMap.get(l.getFromId()), to = idMap.get(l.getToId());
            if (from != null && to != null) {
                links.save(new ThoughtLink(from, to, l.getTypeId(), l.getLabel(), l.isAiSuggested()));
            }
        }
        return new MergeResult(merged, idMap.size());
    }
}