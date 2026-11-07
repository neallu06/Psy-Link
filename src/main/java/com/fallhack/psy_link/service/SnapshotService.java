package com.fallhack.psy_link.service;

import com.fallhack.psy_link.model.IdeaSpace;
import com.fallhack.psy_link.model.SnapshotLink;
import com.fallhack.psy_link.model.SnapshotThought;
import com.fallhack.psy_link.model.SpaceSnapshot;
import com.fallhack.psy_link.model.Thought;
import com.fallhack.psy_link.model.ThoughtLink;
import com.fallhack.psy_link.model.ThoughtVersion;
import com.fallhack.psy_link.repo.SnapshotLinkRepository;
import com.fallhack.psy_link.repo.SnapshotThoughtRepository;
import com.fallhack.psy_link.repo.SpaceSnapshotRepository;
import com.fallhack.psy_link.repo.ThoughtLinkRepository;
import com.fallhack.psy_link.repo.ThoughtRepository;
import com.fallhack.psy_link.repo.ThoughtVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Graph history: save the whole graph of an idea space and bring it back later.
 *
 * Restoring is never a one-way trip: the current graph is saved as its own snapshot first.
 * Thoughts that still exist are updated in place (so their development history stays attached),
 * thoughts deleted since the snapshot are recreated, and thoughts added after it are removed.
 */
@Service
public class SnapshotService {

    public static final int MAX_PER_SPACE = 50;

    private final ThoughtRepository thoughts;
    private final ThoughtLinkRepository links;
    private final ThoughtVersionRepository versions;
    private final SpaceSnapshotRepository snapshots;
    private final SnapshotThoughtRepository snapThoughts;
    private final SnapshotLinkRepository snapLinks;

    public SnapshotService(ThoughtRepository thoughts, ThoughtLinkRepository links, ThoughtVersionRepository versions,
                           SpaceSnapshotRepository snapshots, SnapshotThoughtRepository snapThoughts,
                           SnapshotLinkRepository snapLinks) {
        this.thoughts = thoughts;
        this.links = links;
        this.versions = versions;
        this.snapshots = snapshots;
        this.snapThoughts = snapThoughts;
        this.snapLinks = snapLinks;
    }

    public List<SpaceSnapshot> list(Long spaceId) {
        return snapshots.findBySpaceIdOrderByIdDesc(spaceId);
    }

    public boolean isFull(Long spaceId) {
        return snapshots.countBySpaceId(spaceId) >= MAX_PER_SPACE;
    }

    @Transactional
    public SpaceSnapshot save(IdeaSpace space, String label) {
        List<Thought> ts = thoughts.findBySpaceId(space.getId());
        Set<Long> ids = new HashSet<>();
        ts.forEach(t -> ids.add(t.getId()));
        List<ThoughtLink> ls = ids.isEmpty() ? List.of() : links.findByFromIdInAndToIdIn(ids, ids);

        String name = label == null || label.isBlank() ? "Snapshot" : label.trim();
        SpaceSnapshot snap = snapshots.save(new SpaceSnapshot(space.getId(),
                name.length() > 60 ? name.substring(0, 60) : name, ts.size(), ls.size()));
        ts.forEach(t -> snapThoughts.save(new SnapshotThought(snap.getId(), t)));
        ls.forEach(l -> snapLinks.save(new SnapshotLink(snap.getId(), l)));
        return snap;
    }

    @Transactional
    public void restore(SpaceSnapshot snap, IdeaSpace space) {
        save(space, "Before restoring \"" + snap.getLabel() + "\"");      // makes the restore reversible

        Map<Long, Thought> current = new HashMap<>();
        thoughts.findBySpaceId(space.getId()).forEach(t -> current.put(t.getId(), t));

        Map<Long, Long> idMap = new HashMap<>();          // thought id at snapshot time -> id now
        Set<Long> keep = new HashSet<>();
        for (SnapshotThought s : snapThoughts.findBySnapshotId(snap.getId())) {
            Thought t = current.get(s.getOrigId());
            boolean existed = t != null;
            if (!existed) {
                t = new Thought();
                t.setSpaceId(space.getId());
            }
            boolean changed = existed && (!Objects.equals(t.getTitle(), s.getTitle())
                    || !Objects.equals(t.getText(), s.getText()) || !Objects.equals(t.getCategory(), s.getCategory()));

            t.setTitle(s.getTitle());
            t.setText(s.getText());
            t.setCategory(s.getCategory());
            t.setTags(s.getTags());
            t.setSummary(s.getSummary());
            t.setX(s.getX());
            t.setY(s.getY());
            t.setPinned(s.isPinned());
            t = thoughts.save(t);

            idMap.put(s.getOrigId(), t.getId());
            keep.add(t.getId());
            if (!existed || changed) {
                versions.save(new ThoughtVersion(t.getId(), t.getTitle(), t.getText(), t.getCategory(),
                        "Restored from snapshot \"" + snap.getLabel() + "\""));
            }
        }

        // Thoughts added after the snapshot go away (they are still in the "Before restoring" snapshot).
        for (Thought t : current.values()) {
            if (keep.contains(t.getId())) continue;
            links.deleteAll(links.findByFromIdOrToId(t.getId(), t.getId()));
            versions.deleteAll(versions.findByThoughtIdOrderByIdDesc(t.getId()));
            thoughts.delete(t);
        }

        // Connections: replace the ones among this space's thoughts with the snapshot's.
        if (!keep.isEmpty()) links.deleteAll(links.findByFromIdInAndToIdIn(keep, keep));
        for (SnapshotLink l : snapLinks.findBySnapshotId(snap.getId())) {
            Long from = idMap.get(l.getFromOrigId()), to = idMap.get(l.getToOrigId());
            if (from != null && to != null) {
                links.save(new ThoughtLink(from, to, l.getTypeId(), l.getLabel(), l.isAiSuggested()));
            }
        }
    }

    @Transactional
    public void delete(SpaceSnapshot snap) {
        snapThoughts.deleteAll(snapThoughts.findBySnapshotId(snap.getId()));
        snapLinks.deleteAll(snapLinks.findBySnapshotId(snap.getId()));
        snapshots.delete(snap);
    }

    @Transactional
    public void deleteAllForSpace(Long spaceId) {
        new ArrayList<>(snapshots.findBySpaceIdOrderByIdDesc(spaceId)).forEach(this::delete);
    }
}
