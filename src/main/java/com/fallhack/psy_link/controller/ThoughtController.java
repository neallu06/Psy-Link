package com.fallhack.psy_link.controller;

import com.fallhack.psy_link.model.AppUser;
import com.fallhack.psy_link.model.IdeaSpace;
import com.fallhack.psy_link.model.LinkType;
import com.fallhack.psy_link.model.Thought;
import com.fallhack.psy_link.model.ThoughtLink;
import com.fallhack.psy_link.model.ThoughtVersion;
import com.fallhack.psy_link.repo.IdeaSpaceRepository;
import com.fallhack.psy_link.repo.LinkTypeRepository;
import com.fallhack.psy_link.repo.ThoughtLinkRepository;
import com.fallhack.psy_link.repo.ThoughtRepository;
import com.fallhack.psy_link.repo.ThoughtVersionRepository;
import com.fallhack.psy_link.repo.UserRepository;
import com.fallhack.psy_link.service.GeminiService;
import com.fallhack.psy_link.service.SnapshotService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ThoughtController {

    public record SpaceView(Long id, String name, String description, long thoughtCount) {}
    public record NewSpace(String name, String description) {}
    public record NewThought(String text, Long spaceId) {}
    public record ThoughtUpdate(String title, String text, String category, Double x, Double y, Boolean pinned) {}
    public record NewLink(Long fromId, Long toId, Long typeId, String label, Boolean aiSuggested) {}
    public record LinkUpdate(Long typeId) {}
    public record TypeUpdate(String color, Boolean dotted) {}
    public record NewType(String name, String color, Boolean dotted) {}
    public record RecentView(Long id, String title, Long spaceId, String spaceName) {}
    public record BringRequest(Long thoughtId, Long intoSpaceId, Long relatedToId, Long typeId, String label) {}
    public record Brought(Thought thought, ThoughtLink link) {}

    private final UserRepository users;
    private final IdeaSpaceRepository spaces;
    private final ThoughtRepository thoughts;
    private final ThoughtLinkRepository links;
    private final LinkTypeRepository types;
    private final ThoughtVersionRepository versions;
    private final GeminiService gemini;
    private final SnapshotService snapshotService;

    public ThoughtController(UserRepository users, IdeaSpaceRepository spaces, ThoughtRepository thoughts,
                             ThoughtLinkRepository links, LinkTypeRepository types,
                             ThoughtVersionRepository versions, GeminiService gemini,
                             SnapshotService snapshotService) {
        this.users = users;
        this.spaces = spaces;
        this.thoughts = thoughts;
        this.links = links;
        this.types = types;
        this.versions = versions;
        this.gemini = gemini;
        this.snapshotService = snapshotService;
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

    private SpaceView view(IdeaSpace s) {
        return new SpaceView(s.getId(), s.getName(), s.getDescription(), thoughts.countBySpaceId(s.getId()));
    }

    @GetMapping("/spaces")
    public List<SpaceView> listSpaces(Principal p) {
        AppUser u = me(p);
        List<IdeaSpace> list = spaces.findByOwnerId(u.getId());
        if (list.isEmpty()) {
            list = new ArrayList<>(List.of(spaces.save(new IdeaSpace(u.getId(), "My Thoughts", "Default space"))));
        }
        return list.stream().map(this::view).toList();
    }

    @PostMapping("/spaces")
    public SpaceView createSpace(Principal p, @RequestBody NewSpace body) {
        String name = body.name() == null || body.name().isBlank() ? "Untitled space" : body.name().trim();
        return view(spaces.save(new IdeaSpace(me(p).getId(), clip(name, 60),
                body.description() == null ? "" : body.description())));
    }

    @PutMapping("/spaces/{id}")
    public SpaceView renameSpace(Principal p, @PathVariable Long id, @RequestBody NewSpace body) {
        IdeaSpace s = space(id, me(p));
        if (body.name() != null && !body.name().isBlank()) s.setName(clip(body.name().trim(), 60));
        return view(spaces.save(s));
    }

    @DeleteMapping("/spaces/{id}")
    @Transactional
    public ResponseEntity<Void> deleteSpace(Principal p, @PathVariable Long id) {
        IdeaSpace s = space(id, me(p));
        for (Thought t : thoughts.findBySpaceId(s.getId())) {
            deleteThoughtAndLinks(t.getId());
        }
        snapshotService.deleteAllForSpace(s.getId());
        spaces.delete(s);
        return ResponseEntity.noContent().build();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---------- Thoughts ----------

    @GetMapping("/thoughts")
    public List<Thought> listThoughts(Principal p, @RequestParam Long spaceId) {
        return thoughts.findBySpaceId(space(spaceId, me(p)).getId());
    }

    /**
     * A short idea (a handful of words, one line) is used as the topic on the box exactly as typed.
     * A longer one gets an AI-written topic. Either way the user's own words are kept as the detail.
     */
    static boolean isShortTopic(String text) {
        return text.length() <= 40 && !text.contains("\n") && text.split("\\s+").length <= 6;
    }

    @PostMapping("/thoughts")
    public Thought createThought(Principal p, @RequestBody NewThought body) {
        IdeaSpace s = space(body.spaceId(), me(p));
        String text = body.text() == null ? "" : body.text().trim();
        if (text.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write a thought first.");
        boolean shortTopic = isShortTopic(text);

        Thought t = new Thought();
        t.setText(text);
        t.setSpaceId(s.getId());
        try {
            GeminiService.Analysis a = gemini.analyze(text);
            t.setTitle(shortTopic ? text : (a.title().isBlank() ? fallbackTitle(text) : a.title()));
            t.setCategory(a.category());
            t.setTags(a.tags());
            t.setSummary(a.summary());
        } catch (Exception e) {
            // Never lose a thought because the AI call failed.
            t.setTitle(shortTopic ? text : fallbackTitle(text));
            t.setCategory("Uncategorized");
        }
        Thought saved = thoughts.save(t);
        versions.save(new ThoughtVersion(saved.getId(), saved.getTitle(), saved.getText(), saved.getCategory(), "Created"));
        return saved;
    }

    // ---------- Recents (global or one space) ----------

    @GetMapping("/recent")
    public List<RecentView> recent(Principal p, @RequestParam(required = false) Long spaceId,
                                   @RequestParam(defaultValue = "8") int limit) {
        AppUser u = me(p);
        List<IdeaSpace> mine = spaceId == null ? spaces.findByOwnerId(u.getId()) : List.of(space(spaceId, u));
        if (mine.isEmpty()) return List.of();
        Map<Long, String> names = new LinkedHashMap<>();
        mine.forEach(s -> names.put(s.getId(), s.getName()));
        int n = Math.max(1, Math.min(limit, 20));
        return thoughts.findBySpaceIdInOrderByIdDesc(names.keySet(), PageRequest.of(0, n)).stream()
                .map(t -> new RecentView(t.getId(),
                        t.getTitle() == null || t.getTitle().isBlank() ? clip(t.getText(), 24) : t.getTitle(),
                        t.getSpaceId(), names.get(t.getSpaceId())))
                .toList();
    }

    /**
     * Copies a thought from another space into this one and links the copy to a related thought here.
     * Used when the user accepts one of Psy's suggested bridges between spaces. The original is untouched.
     */
    @PostMapping("/thoughts/bring")
    @Transactional
    public Brought bring(Principal p, @RequestBody BringRequest body) {
        AppUser u = me(p);
        Thought src = thought(body.thoughtId(), u);
        IdeaSpace target = space(body.intoSpaceId(), u);
        Thought related = thought(body.relatedToId(), u);
        type(body.typeId(), u);
        if (!target.getId().equals(related.getSpaceId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (target.getId().equals(src.getSpaceId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);

        Thought c = new Thought();
        c.setTitle(src.getTitle());
        c.setText(src.getText());
        c.setCategory(src.getCategory());
        c.setTags(src.getTags());
        c.setSummary(src.getSummary());
        c.setSpaceId(target.getId());
        c = thoughts.save(c);
        IdeaSpace from = space(src.getSpaceId(), u);
        versions.save(new ThoughtVersion(c.getId(), c.getTitle(), c.getText(), c.getCategory(), "Brought from " + from.getName()));

        String label = body.label() == null || body.label().isBlank() ? null : clip(body.label().trim(), 200);
        ThoughtLink l = links.save(new ThoughtLink(c.getId(), related.getId(), body.typeId(), label, true));
        return new Brought(c, l);
    }

    private static String fallbackTitle(String text) {
        String[] words = text.trim().split("\\s+");
        String joined = String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 5)));
        return words.length > 5 ? joined + "…" : joined;
    }

    @PutMapping("/thoughts/{id}")
    public Thought updateThought(Principal p, @PathVariable Long id, @RequestBody ThoughtUpdate body) {
        Thought t = thought(id, me(p));
        String oldTitle = t.getTitle(), oldText = t.getText(), oldCategory = t.getCategory();

        boolean changed = false;
        if (body.title() != null && !body.title().equals(t.getTitle())) { t.setTitle(body.title()); changed = true; }
        if (body.text() != null && !body.text().equals(t.getText())) { t.setText(body.text()); changed = true; }
        if (body.category() != null) t.setCategory(body.category());
        if (body.x() != null) t.setX(body.x());
        if (body.y() != null) t.setY(body.y());
        if (body.pinned() != null) t.setPinned(body.pinned());

        // History: remember the original wording the first time a thought is edited, then every later edit.
        if (changed && versions.countByThoughtId(id) == 0) {
            versions.save(new ThoughtVersion(id, oldTitle, oldText, oldCategory, "Original"));
        }
        Thought saved = thoughts.save(t);
        if (changed) {
            versions.save(new ThoughtVersion(id, saved.getTitle(), saved.getText(), saved.getCategory(), "Edited"));
        }
        return saved;
    }

    @GetMapping("/thoughts/{id}/history")
    public List<ThoughtVersion> history(Principal p, @PathVariable Long id) {
        thought(id, me(p));
        return versions.findByThoughtIdOrderByIdDesc(id);
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
        versions.deleteAll(versions.findByThoughtIdOrderByIdDesc(id));
        thoughts.deleteById(id);
    }

    // ---------- Connection types (colors / line style) ----------

    @GetMapping("/types")
    public List<LinkType> listTypes(Principal p) {
        return types.findByOwnerId(me(p).getId());
    }

    @PostMapping("/types")
    public ResponseEntity<?> createType(Principal p, @RequestBody NewType body) {
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty() || name.length() > 40) {
            return ResponseEntity.badRequest().body(Map.of("message", "Give the connection a name (up to 40 characters)."));
        }
        String color = body.color() != null && body.color().matches("#[0-9a-fA-F]{6}") ? body.color() : "#8a7bff";
        String key = "custom_" + UUID.randomUUID().toString().substring(0, 8);
        LinkType t = new LinkType(me(p).getId(), key, name, "Custom connection.", color, Boolean.TRUE.equals(body.dotted()));
        return ResponseEntity.ok(types.save(t));
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
        String label = body.label() == null || body.label().isBlank() ? null : clip(body.label().trim(), 200);
        return links.save(new ThoughtLink(body.fromId(), body.toId(), body.typeId(), label,
                Boolean.TRUE.equals(body.aiSuggested())));
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
}