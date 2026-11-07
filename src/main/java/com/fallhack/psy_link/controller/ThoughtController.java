package com.fallhack.psy_link.controller;

import com.fallhack.psy_link.model.AppUser;
import com.fallhack.psy_link.model.IdeaSpace;
import com.fallhack.psy_link.model.LinkType;
import com.fallhack.psy_link.model.Thought;
import com.fallhack.psy_link.model.ThoughtLink;
import com.fallhack.psy_link.repo.IdeaSpaceRepository;
import com.fallhack.psy_link.repo.LinkTypeRepository;
import com.fallhack.psy_link.repo.ThoughtLinkRepository;
import com.fallhack.psy_link.repo.ThoughtRepository;
import com.fallhack.psy_link.repo.UserRepository;
import com.fallhack.psy_link.service.GeminiService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ThoughtController {

    public record NewSpace(String name, String description) {}
    public record NewThought(String text, Long spaceId) {}
    public record ThoughtUpdate(String title, String text, Double x, Double y) {}
    public record NewLink(Long fromId, Long toId, Long typeId) {}
    public record LinkUpdate(Long typeId) {}
    public record TypeUpdate(String color, Boolean dotted) {}
    public record Question(String question, List<Long> thoughtIds) {}

    private final UserRepository users;
    private final IdeaSpaceRepository spaces;
    private final ThoughtRepository thoughts;
    private final ThoughtLinkRepository links;
    private final LinkTypeRepository types;
    private final GeminiService gemini;

    public ThoughtController(UserRepository users, IdeaSpaceRepository spaces, ThoughtRepository thoughts,
                             ThoughtLinkRepository links, LinkTypeRepository types, GeminiService gemini) {
        this.users = users;
        this.spaces = spaces;
        this.thoughts = thoughts;
        this.links = links;
        this.types = types;
        this.gemini = gemini;
    }

    // ---------- ownership helpers: every lookup is scoped to the logged-in user ----------

    private AppUser me(Principal p) {
        return users.findByUsername(p.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private IdeaSpace space(Long id, AppUser u) {
        return spaces.findById(id).filter(s -> u.getId().equals(s.getOwnerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Thought thought(Long id, AppUser u) {
        Thought t = thoughts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        space(t.getSpaceId(), u);
        return t;
    }

    private LinkType type(Long id, AppUser u) {
        return types.findById(id).filter(t -> u.getId().equals(t.getOwnerId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ---------- Idea Spaces ----------

    @GetMapping("/spaces")
    public List<IdeaSpace> listSpaces(Principal p) {
        AppUser u = me(p);
        List<IdeaSpace> list = spaces.findByOwnerId(u.getId());
        if (list.isEmpty()) {
            list = new ArrayList<>(List.of(spaces.save(new IdeaSpace(u.getId(), "My Thoughts", "Default space"))));
        }
        return list;
    }

    @PostMapping("/spaces")
    public IdeaSpace createSpace(Principal p, @RequestBody NewSpace body) {
        return spaces.save(new IdeaSpace(me(p).getId(), body.name(), body.description()));
    }

    @DeleteMapping("/spaces/{id}")
    @Transactional
    public ResponseEntity<Void> deleteSpace(Principal p, @PathVariable Long id) {
        IdeaSpace s = space(id, me(p));
        for (Thought t : thoughts.findBySpaceId(s.getId())) {
            deleteThoughtAndLinks(t.getId());
        }
        spaces.delete(s);
        return ResponseEntity.noContent().build();
    }

    // ---------- Thoughts ----------

    @GetMapping("/thoughts")
    public List<Thought> listThoughts(Principal p, @RequestParam Long spaceId) {
        return thoughts.findBySpaceId(space(spaceId, me(p)).getId());
    }

    @PostMapping("/thoughts")
    public Thought createThought(Principal p, @RequestBody NewThought body) {
        IdeaSpace s = space(body.spaceId(), me(p));
        Thought t = new Thought();
        t.setText(body.text());
        t.setSpaceId(s.getId());
        try {
            GeminiService.Analysis a = gemini.analyze(body.text());
            t.setTitle(a.title().isBlank() ? fallbackTitle(body.text()) : a.title());
            t.setCategory(a.category());
            t.setTags(a.tags());
            t.setSummary(a.summary());
        } catch (Exception e) {
            // Never lose a thought because the AI call failed.
            t.setTitle(fallbackTitle(body.text()));
            t.setCategory("Uncategorized");
        }
        return thoughts.save(t);
    }

    private static String fallbackTitle(String text) {
        String[] words = text.trim().split("\\s+");
        String joined = String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 5)));
        return words.length > 5 ? joined + "…" : joined;
    }

    @PutMapping("/thoughts/{id}")
    public Thought updateThought(Principal p, @PathVariable Long id, @RequestBody ThoughtUpdate body) {
        Thought t = thought(id, me(p));
        if (body.title() != null) t.setTitle(body.title());
        if (body.text() != null) t.setText(body.text());
        if (body.x() != null) t.setX(body.x());
        if (body.y() != null) t.setY(body.y());
        return thoughts.save(t);
    }

    @DeleteMapping("/thoughts/{id}")
    @Transactional
    public ResponseEntity<Void> deleteThought(Principal p, @PathVariable Long id) {
        thought(id, me(p));
        deleteThoughtAndLinks(id);
        return ResponseEntity.noContent().build();
    }

    private void deleteThoughtAndLinks(Long id) {
        links.deleteAll(links.findByFromIdOrToId(id, id));
        thoughts.deleteById(id);
    }

    // ---------- Connection types (colors / line style) ----------

    @GetMapping("/types")
    public List<LinkType> listTypes(Principal p) {
        return types.findByOwnerId(me(p).getId());
    }

    @PutMapping("/types/{id}")
    public LinkType updateType(Principal p, @PathVariable Long id, @RequestBody TypeUpdate body) {
        LinkType t = type(id, me(p));
        if (body.color() != null && body.color().matches("#[0-9a-fA-F]{6}")) t.setColor(body.color());
        if (body.dotted() != null) t.setDotted(body.dotted());
        return types.save(t);
    }

    // ---------- Connections ----------

    @GetMapping("/links")
    public List<ThoughtLink> listLinks(Principal p, @RequestParam Long spaceId) {
        return linksInSpace(space(spaceId, me(p)).getId());
    }

    private List<ThoughtLink> linksInSpace(Long spaceId) {
        Set<Long> ids = new HashSet<>();
        thoughts.findBySpaceId(spaceId).forEach(t -> ids.add(t.getId()));
        if (ids.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(links.findByFromIdInAndToIdIn(ids, ids));
    }

    @PostMapping("/links")
    public ThoughtLink createLink(Principal p, @RequestBody NewLink body) {
        AppUser u = me(p);
        thought(body.fromId(), u);
        thought(body.toId(), u);
        type(body.typeId(), u);
        return links.save(new ThoughtLink(body.fromId(), body.toId(), body.typeId(), null, false));
    }

    @PutMapping("/links/{id}")
    public ThoughtLink updateLink(Principal p, @PathVariable Long id, @RequestBody LinkUpdate body) {
        AppUser u = me(p);
        ThoughtLink l = ownedLink(id, u);
        type(body.typeId(), u);
        l.setTypeId(body.typeId());
        return links.save(l);
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<Void> deleteLink(Principal p, @PathVariable Long id) {
        links.delete(ownedLink(id, me(p)));
        return ResponseEntity.noContent().build();
    }

    private ThoughtLink ownedLink(Long id, AppUser u) {
        ThoughtLink l = links.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        thought(l.getFromId(), u);
        return l;
    }

    /** AI proposes typed connections for a space; saved flagged aiSuggested, and the user can change or delete them. */
    @PostMapping("/spaces/{id}/suggest-links")
    public List<ThoughtLink> suggestLinks(Principal p, @PathVariable Long id) {
        AppUser u = me(p);
        IdeaSpace s = space(id, u);
        List<Thought> inSpace = thoughts.findBySpaceId(s.getId());
        Set<Long> valid = new HashSet<>();
        inSpace.forEach(t -> valid.add(t.getId()));

        Map<String, Long> typeIdByKey = new HashMap<>();
        types.findByOwnerId(u.getId()).forEach(t -> typeIdByKey.put(t.getKey(), t.getId()));

        List<ThoughtLink> existing = linksInSpace(s.getId());
        List<ThoughtLink> created = new ArrayList<>();
        for (GeminiService.SuggestedLink sl : gemini.suggestLinks(inSpace)) {
            if (!valid.contains(sl.fromId()) || !valid.contains(sl.toId()) || sl.fromId().equals(sl.toId())) continue;
            boolean dup = existing.stream().anyMatch(l ->
                    (l.getFromId().equals(sl.fromId()) && l.getToId().equals(sl.toId())) ||
                    (l.getFromId().equals(sl.toId()) && l.getToId().equals(sl.fromId())));
            if (dup) continue;
            Long typeId = typeIdByKey.getOrDefault(sl.typeKey(), typeIdByKey.get("related"));
            ThoughtLink saved = links.save(new ThoughtLink(sl.fromId(), sl.toId(), typeId, sl.reason(), true));
            existing.add(saved);
            created.add(saved);
        }
        return created;
    }

    // ---------- Gemini chat ----------

    @PostMapping("/ask")
    public Map<String, String> ask(Principal p, @RequestBody Question body) {
        AppUser u = me(p);
        List<Thought> context = new ArrayList<>();
        if (body.thoughtIds() != null) {
            for (Thought t : thoughts.findAllById(body.thoughtIds())) {
                try {
                    thought(t.getId(), u);   // skip anything that isn't the user's
                    context.add(t);
                } catch (ResponseStatusException ignored) { }
            }
        }
        return Map.of("answer", gemini.ask(body.question(), context));
    }
}