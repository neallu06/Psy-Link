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
import com.fallhack.psy_link.service.AccessService;
import com.fallhack.psy_link.service.GeminiService;
import com.fallhack.psy_link.service.SnapshotService;
import com.fallhack.psy_link.service.SpaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Psy, the chat companion. Nothing here runs unless the user's message asks for it:
 * the message is classified first, and anything that is not a clear request is plain conversation.
 * Suggestions (links, categories, bridges) come back as "actions" the user must accept in the UI.
 */
@RestController
@RequestMapping("/api/psy")
public class PsyController {

    private static final Logger log = LoggerFactory.getLogger(PsyController.class);
    private static final int SEARCH_POOL = 250;

    public record PsyRequest(String message, Long spaceId, List<GeminiService.Turn> history) {}

    /** One thing Psy offers that the user can accept: link, category, open (search hit) or bridge. */
    public record Action(String kind, Long fromId, Long toId, Long typeId, String typeName,
                         String fromTitle, String toTitle, String reason,
                         Long thoughtId, String category, Long spaceId, String spaceName) {
        static Action link(Long from, Long to, Long typeId, String typeName, String fromTitle, String toTitle, String reason) {
            return new Action("link", from, to, typeId, typeName, fromTitle, toTitle, reason, null, null, null, null);
        }
        static Action category(Long thoughtId, String title, String category) {
            return new Action("category", null, null, null, null, title, null, null, thoughtId, category, null, null);
        }
        static Action open(Long thoughtId, String title, String reason, Long spaceId, String spaceName) {
            return new Action("open", null, null, null, null, title, null, reason, thoughtId, null, spaceId, spaceName);
        }
        /** Bring thought `fromId` (living in spaceName) into space `spaceId` and link it to `toId` there. */
        static Action bridge(Long from, Long to, Long typeId, String typeName, String fromTitle, String toTitle,
                             String reason, Long intoSpaceId, String fromSpaceName) {
            return new Action("bridge", from, to, typeId, typeName, fromTitle, toTitle, reason, null, null, intoSpaceId, fromSpaceName);
        }
    }

    public record PsyReply(String reply, List<Action> actions, Long openSpaceId, String openSpaceName, boolean spacesChanged) {}

    private final AccessService access;
    private final IdeaSpaceRepository spaces;
    private final ThoughtRepository thoughts;
    private final ThoughtLinkRepository links;
    private final LinkTypeRepository types;
    private final ThoughtVersionRepository versions;
    private final GeminiService gemini;
    private final SpaceService spaceService;
    private final SnapshotService snapshotService;

    public PsyController(AccessService access, IdeaSpaceRepository spaces, ThoughtRepository thoughts,
                         ThoughtLinkRepository links, LinkTypeRepository types, ThoughtVersionRepository versions,
                         GeminiService gemini, SpaceService spaceService, SnapshotService snapshotService) {
        this.access = access;
        this.spaces = spaces;
        this.thoughts = thoughts;
        this.links = links;
        this.types = types;
        this.versions = versions;
        this.gemini = gemini;
        this.spaceService = spaceService;
        this.snapshotService = snapshotService;
    }

    private static PsyReply plain(String text) {
        return new PsyReply(text, List.of(), null, null, false);
    }

    @PostMapping
    public PsyReply talk(Principal p, @RequestBody PsyRequest body) {
        AppUser u = access.me(p);
        String msg = body.message() == null ? "" : body.message().trim();
        if (msg.isEmpty()) return plain("I'm listening. What's on your mind?");

        List<GeminiService.Turn> history = body.history() == null ? List.of() : body.history();
        List<IdeaSpace> mine = spaces.findByOwnerId(u.getId());
        IdeaSpace current = body.spaceId() == null ? null
                : mine.stream().filter(s -> s.getId().equals(body.spaceId())).findFirst().orElse(null);

        // If only the routing step fails, Psy still holds a normal conversation.
        GeminiService.Intent intent;
        try {
            intent = gemini.route(msg, history, mine.stream().map(IdeaSpace::getName).toList(),
                    current == null ? "(none, the user is on the home page)" : current.getName());
        } catch (Exception e) {
            log.warn("Psy routing failed, falling back to plain conversation: {}", e.toString());
            intent = new GeminiService.Intent("CHAT", List.of());
        }

        try {
            return switch (intent.kind()) {
                case "LINKS" -> doLinks(u, current);
                case "CATEGORIES" -> doCategories(current);
                case "ANALYZE" -> doAnalyze(mine, current, intent.arg(0), null, msg, history);
                case "PERSPECTIVE" -> doAnalyze(mine, current, intent.arg(1), intent.arg(0), msg, history);
                case "MERGE" -> doMerge(u, mine, intent.arg(0), intent.arg(1), intent.arg(2));
                case "HISTORY" -> doHistory(current, intent.arg(0));
                case "SEARCH" -> doSearch(mine, intent.arg(0).isBlank() ? msg : intent.arg(0));
                case "PATTERNS" -> doPatterns(mine, msg, history);
                case "PROMPTS" -> doPrompts(mine, current, intent.arg(0), msg);
                case "BRIDGES" -> doBridges(u, mine, current, intent.arg(0), intent.arg(1));
                case "SNAPSHOT" -> doSnapshot(current, intent.arg(0));
                default -> doChat(current, msg, history);
            };
        } catch (Exception e) {
            log.warn("Psy request failed ({}): {}", intent.kind(), e.toString(), e);
            return plain(GeminiService.friendlyError(e));
        }
    }

    // ------------------------------------------------------------------ v2 actions

    private PsyReply doLinks(AppUser u, IdeaSpace current) {
        if (current == null) return plain("Open an idea space first and ask me again. I'll look for connections inside it.");
        List<Thought> inSpace = thoughts.findBySpaceId(current.getId());
        if (inSpace.size() < 2) return plain("I need at least two thoughts in this space to look for connections. What else is on your mind?");

        Map<Long, Thought> byId = new HashMap<>();
        inSpace.forEach(t -> byId.put(t.getId(), t));
        Map<String, LinkType> typeByKey = new HashMap<>();
        types.findByOwnerId(u.getId()).forEach(t -> typeByKey.put(t.getKey(), t));

        Set<Long> ids = byId.keySet();
        Set<String> taken = new HashSet<>();
        for (ThoughtLink l : links.findByFromIdInAndToIdIn(ids, ids)) taken.add(pair(l.getFromId(), l.getToId()));

        List<Action> actions = new ArrayList<>();
        for (GeminiService.SuggestedLink sl : gemini.suggestLinks(inSpace)) {
            if (!byId.containsKey(sl.fromId()) || !byId.containsKey(sl.toId()) || sl.fromId().equals(sl.toId())) continue;
            if (!taken.add(pair(sl.fromId(), sl.toId()))) continue;
            LinkType lt = typeByKey.getOrDefault(sl.typeKey(), typeByKey.get("related"));
            if (lt == null) continue;
            actions.add(Action.link(sl.fromId(), sl.toId(), lt.getId(), lt.getName(),
                    title(byId.get(sl.fromId())), title(byId.get(sl.toId())), sl.reason()));
        }
        if (actions.isEmpty()) {
            return plain("I looked through this space and nothing stands out as a strong new connection yet. More thoughts might reveal some.");
        }
        return new PsyReply("Here are " + actions.size() + " connections I noticed. They're only suggestions, so add the ones that feel right to you.",
                actions, null, null, false);
    }

    private PsyReply doCategories(IdeaSpace current) {
        if (current == null) return plain("Open an idea space first and ask me again. I'll suggest categories for its thoughts.");
        List<Thought> inSpace = thoughts.findBySpaceId(current.getId());
        if (inSpace.isEmpty()) return plain("This space has no thoughts yet. Write a few down and I'll happily suggest categories.");

        Map<Long, Thought> byId = new HashMap<>();
        inSpace.forEach(t -> byId.put(t.getId(), t));
        List<Action> actions = new ArrayList<>();
        for (GeminiService.SuggestedCategory sc : gemini.suggestCategories(inSpace)) {
            Thought t = byId.get(sc.thoughtId());
            if (t == null || sc.category().equalsIgnoreCase(t.getCategory() == null ? "" : t.getCategory())) continue;
            actions.add(Action.category(t.getId(), title(t), sc.category()));
        }
        if (actions.isEmpty()) return plain("Your current categories already look sensible to me. Nothing to change.");
        return new PsyReply("Here are some category ideas. Apply any that fit, and ignore the rest.", actions, null, null, false);
    }

    private PsyReply doAnalyze(List<IdeaSpace> mine, IdeaSpace current, String spaceArg, String lens,
                               String msg, List<GeminiService.Turn> history) {
        IdeaSpace target = (spaceArg == null || spaceArg.isBlank()) ? current : findSpace(mine, spaceArg, null);
        if (target == null) {
            return plain(spaceArg == null || spaceArg.isBlank()
                    ? "Open an idea space, or tell me which one to look at."
                    : "I couldn't find a space called \"" + spaceArg + "\". Check the name on the home page?");
        }
        List<Thought> inSpace = thoughts.findBySpaceId(target.getId());
        if (inSpace.isEmpty()) return plain("\"" + target.getName() + "\" has no thoughts yet. Add a few and I'll take a look with you.");
        return plain(gemini.analyzeSpace(target.getName(), inSpace, lens, msg, history));
    }

    private PsyReply doMerge(AppUser u, List<IdeaSpace> mine, String nameA, String nameB, String newName) {
        IdeaSpace a = findSpace(mine, nameA, null);
        IdeaSpace b = findSpace(mine, nameB, a);
        if (a == null || b == null) {
            return plain("Tell me which two spaces to merge, for example: \"merge Work and Health\". I'll make a new space and leave both originals as they are.");
        }
        String name = (newName == null || newName.isBlank()) ? a.getName() + " + " + b.getName() : newName.trim();
        SpaceService.MergeResult r = spaceService.merge(u.getId(), a, b, name);

        String synthesis;
        try {
            synthesis = gemini.synthesizeMerge(a.getName(), thoughts.findBySpaceId(a.getId()),
                    b.getName(), thoughts.findBySpaceId(b.getId()));
        } catch (Exception e) {
            synthesis = "Take a look around and see what new ideas the two spaces spark.";
        }
        String text = "I created \"" + r.space().getName() + "\" with " + r.thoughtCount()
                + " thoughts from both spaces. Your originals are untouched.\n\n" + synthesis;
        return new PsyReply(text, List.of(), r.space().getId(), r.space().getName(), true);
    }

    private PsyReply doHistory(IdeaSpace current, String needle) {
        if (current == null) return plain("Open the idea space that holds the thought, then ask me how it has developed.");
        String n = needle == null ? "" : needle.trim().toLowerCase();
        Thought found = null;
        if (!n.isEmpty()) {
            for (Thought t : thoughts.findBySpaceId(current.getId())) {
                String ti = t.getTitle() == null ? "" : t.getTitle().toLowerCase();
                if (ti.equals(n) || ti.contains(n) || n.contains(ti) && !ti.isEmpty()) { found = t; break; }
            }
        }
        if (found == null) return plain("Which thought do you mean? Tell me its title and I'll trace how it has changed.");

        List<ThoughtVersion> vs = versions.findByThoughtIdOrderByIdAsc(found.getId());
        if (vs.size() < 2) return plain("\"" + title(found) + "\" hasn't changed since you first wrote it, so there's no story to trace yet.");
        return plain(gemini.describeHistory(found, vs));
    }

    private PsyReply doChat(IdeaSpace current, String msg, List<GeminiService.Turn> history) {
        List<Thought> ctx = current == null ? List.of() : thoughts.findBySpaceId(current.getId());
        return plain(gemini.converse(msg, history, current == null ? null : current.getName(), ctx));
    }

    // ------------------------------------------------------------------ v3 actions

    /** Semantic search across every idea space. Falls back to keyword matching if Gemini can't be reached. */
    private PsyReply doSearch(List<IdeaSpace> mine, String query) {
        Map<Long, String> names = new LinkedHashMap<>();
        mine.forEach(s -> names.put(s.getId(), s.getName()));
        List<Thought> pool = names.isEmpty() ? List.of()
                : thoughts.findBySpaceIdInOrderByIdDesc(names.keySet(), org.springframework.data.domain.PageRequest.of(0, SEARCH_POOL));
        if (pool.isEmpty()) return plain("You haven't saved any thoughts yet. Write one down and I can help you find it later.");

        Map<Long, Thought> byId = new HashMap<>();
        pool.forEach(t -> byId.put(t.getId(), t));

        List<GeminiService.SearchHit> hits;
        boolean keywordOnly = false;
        try {
            hits = gemini.search(query, pool, names);
        } catch (Exception e) {
            log.warn("Semantic search failed, using keyword matching: {}", e.toString());
            hits = List.of();
            keywordOnly = true;
        }
        if (hits.isEmpty()) {
            hits = keywordSearch(query, pool);
            if (!hits.isEmpty() && !keywordOnly) keywordOnly = true;
        }
        if (hits.isEmpty()) return plain("I couldn't find a thought that matches \"" + query + "\". Maybe it's still waiting to be written?");

        List<Action> actions = new ArrayList<>();
        for (GeminiService.SearchHit h : hits) {
            Thought t = byId.get(h.thoughtId());
            if (t != null) actions.add(Action.open(t.getId(), title(t), h.reason(), t.getSpaceId(), names.get(t.getSpaceId())));
        }
        String intro = "Here's what I found for \"" + query + "\" across your idea spaces.";
        if (keywordOnly) intro += " (I matched on words this time, so meaning-based results may be missing.)";
        return new PsyReply(intro, actions, null, null, false);
    }

    private static List<GeminiService.SearchHit> keywordSearch(String query, List<Thought> pool) {
        List<String> words = java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 3).toList();
        if (words.isEmpty()) return List.of();
        Map<Thought, Integer> score = new HashMap<>();
        for (Thought t : pool) {
            String hay = ((t.getTitle() == null ? "" : t.getTitle()) + " " + (t.getText() == null ? "" : t.getText())).toLowerCase(Locale.ROOT);
            int s = 0;
            for (String w : words) if (hay.contains(w)) s++;
            if (s > 0) score.put(t, s);
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<Thought, Integer>comparingByValue().reversed())
                .limit(8)
                .map(e -> new GeminiService.SearchHit(e.getKey().getId(), "matches your words"))
                .toList();
    }

    /** Thinking patterns: counted on the server, narrated by Gemini. */
    private PsyReply doPatterns(List<IdeaSpace> mine, String msg, List<GeminiService.Turn> history) {
        Map<Long, String> names = new LinkedHashMap<>();
        mine.forEach(s -> names.put(s.getId(), s.getName()));
        List<Thought> all = names.isEmpty() ? List.of()
                : thoughts.findBySpaceIdInOrderByIdDesc(names.keySet(), org.springframework.data.domain.PageRequest.of(0, 1000));
        if (all.size() < 5) {
            return plain("I'd need a few more of your thoughts to notice real patterns. You have " + all.size()
                    + " so far. Keep writing, and ask me again when there's more to look at!");
        }
        return plain(gemini.thinkingPatterns(patternStats(all, names), all.stream().limit(30).toList(), history, msg));
    }

    private String patternStats(List<Thought> all, Map<Long, String> spaceNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thoughts written: ").append(all.size()).append(" across ").append(spaceNames.size()).append(" idea spaces.\n");

        Map<Long, Long> perSpace = all.stream().collect(Collectors.groupingBy(Thought::getSpaceId, Collectors.counting()));
        sb.append("Thoughts per space: ");
        perSpace.forEach((id, n) -> sb.append(spaceNames.get(id)).append(" (").append(n).append("), "));
        sb.append('\n');

        Map<String, Long> cats = all.stream()
                .collect(Collectors.groupingBy(t -> t.getCategory() == null || t.getCategory().isBlank() ? "Uncategorized" : t.getCategory(), Collectors.counting()));
        sb.append("Most common categories: ");
        cats.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(6)
                .forEach(e -> sb.append(e.getKey()).append(" (").append(e.getValue()).append("), "));
        sb.append('\n');

        double avgWords = all.stream().mapToInt(t -> t.getText() == null ? 0 : t.getText().trim().split("\\s+").length).average().orElse(0);
        long shortOnes = all.stream().filter(t -> t.getText() != null && t.getText().trim().split("\\s+").length <= 6).count();
        long longOnes = all.stream().filter(t -> t.getText() != null && t.getText().trim().split("\\s+").length >= 30).count();
        sb.append(String.format(Locale.ROOT, "Average length: %.0f words. Short notes (6 words or fewer): %d. Long reflections (30+ words): %d.%n", avgWords, shortOnes, longOnes));

        long pinned = all.stream().filter(Thought::isPinned).count();
        sb.append("Pinned thoughts: ").append(pinned).append('\n');

        Set<Long> ids = all.stream().map(Thought::getId).collect(Collectors.toSet());
        List<ThoughtLink> ls = links.findByFromIdInAndToIdIn(ids, ids);
        Set<Long> connected = new HashSet<>();
        ls.forEach(l -> { connected.add(l.getFromId()); connected.add(l.getToId()); });
        Map<Long, String> typeNames = new HashMap<>();
        types.findAllById(ls.stream().map(ThoughtLink::getTypeId).collect(Collectors.toSet())).forEach(t -> typeNames.put(t.getId(), t.getName()));
        Map<String, Long> byType = ls.stream().collect(Collectors.groupingBy(l -> typeNames.getOrDefault(l.getTypeId(), "Other"), Collectors.counting()));
        sb.append("Connections drawn: ").append(ls.size()).append(". By type: ");
        byType.forEach((k, v) -> sb.append(k).append(" (").append(v).append("), "));
        sb.append("\nThoughts with no connection yet: ").append(all.size() - connected.size()).append('\n');

        Map<Long, Integer> versionCounts = new HashMap<>();
        for (Thought t : all.stream().limit(200).toList()) versionCounts.put(t.getId(), (int) versions.countByThoughtId(t.getId()));
        long revised = versionCounts.values().stream().filter(n -> n > 1).count();
        sb.append("Thoughts revised at least once: ").append(revised).append(" of ").append(versionCounts.size()).append(" checked.\n");
        Map<Long, Thought> byId = all.stream().collect(Collectors.toMap(Thought::getId, t -> t));
        sb.append("Most revised: ");
        versionCounts.entrySet().stream().sorted(Map.Entry.<Long, Integer>comparingByValue().reversed()).limit(3)
                .filter(e -> e.getValue() > 1)
                .forEach(e -> sb.append(title(byId.get(e.getKey()))).append(" (").append(e.getValue()).append(" versions), "));
        sb.append('\n');
        return sb.toString();
    }

    private PsyReply doPrompts(List<IdeaSpace> mine, IdeaSpace current, String spaceArg, String msg) {
        IdeaSpace target = (spaceArg == null || spaceArg.isBlank()) ? current : findSpace(mine, spaceArg, null);
        List<Thought> ts = target == null ? List.of() : thoughts.findBySpaceId(target.getId());
        return plain(gemini.explorationPrompts(target == null ? "" : target.getName(), ts, msg));
    }

    /** Suggests connections between a space and the user's other, previously unconnected spaces. */
    private PsyReply doBridges(AppUser u, List<IdeaSpace> mine, IdeaSpace current, String argA, String argB) {
        IdeaSpace a = (argA == null || argA.isBlank()) ? current : findSpace(mine, argA, null);
        if (a == null) return plain("Open an idea space, or tell me which one to start from, and I'll look for bridges to your other spaces.");

        List<IdeaSpace> others;
        if (argB != null && !argB.isBlank()) {
            IdeaSpace b = findSpace(mine, argB, a);
            if (b == null) return plain("I couldn't find a space called \"" + argB + "\". Check the name on the home page?");
            others = List.of(b);
        } else {
            others = mine.stream().filter(s -> !s.getId().equals(a.getId())).toList();
        }
        if (others.isEmpty()) return plain("You only have one idea space so far. Once there's a second, I can look for bridges between them.");

        List<Thought> aThoughts = thoughts.findBySpaceId(a.getId());
        Map<Long, String> names = new HashMap<>();
        List<Thought> bThoughts = new ArrayList<>();
        for (IdeaSpace o : others) {
            names.put(o.getId(), o.getName());
            bThoughts.addAll(thoughts.findBySpaceId(o.getId()));
        }
        if (aThoughts.isEmpty() || bThoughts.isEmpty()) {
            return plain("I need thoughts in both places to find bridges. Add a few to \"" + a.getName() + "\" and your other spaces first.");
        }
        bThoughts.sort(Comparator.comparing(Thought::getId).reversed());
        if (bThoughts.size() > 80) bThoughts = new ArrayList<>(bThoughts.subList(0, 80));

        Map<Long, Thought> aById = aThoughts.stream().collect(Collectors.toMap(Thought::getId, t -> t));
        Map<Long, Thought> bById = bThoughts.stream().collect(Collectors.toMap(Thought::getId, t -> t));
        List<LinkType> myTypes = types.findByOwnerId(u.getId());
        LinkType related = myTypes.stream().filter(t -> "related".equals(t.getKey())).findFirst()
                .orElse(myTypes.isEmpty() ? null : myTypes.get(0));
        if (related == null) return plain("You don't have any connection types yet. Add one in the sidebar and ask me again.");

        List<Action> actions = new ArrayList<>();
        for (GeminiService.Bridge br : gemini.bridges(a.getName(), aThoughts, bThoughts, names)) {
            Thought ta = aById.get(br.aId()), tb = bById.get(br.bId());
            if (ta == null || tb == null) continue;
            actions.add(Action.bridge(tb.getId(), ta.getId(), related.getId(), related.getName(),
                    title(tb), title(ta), br.reason(), a.getId(), names.get(tb.getSpaceId())));
        }
        if (actions.isEmpty()) return plain("I looked for bridges between \"" + a.getName() + "\" and your other spaces and nothing stood out yet. That can change as they grow.");
        return new PsyReply("I found " + actions.size() + " possible bridges between \"" + a.getName() + "\" and your other spaces. "
                + "Bringing one over copies that thought into this space and links it. The original stays where it is.",
                actions, null, null, false);
    }

    private PsyReply doSnapshot(IdeaSpace current, String label) {
        if (current == null) return plain("Open the idea space you'd like to save, then ask me again.");
        if (snapshotService.isFull(current.getId())) {
            return plain("This space already has " + SnapshotService.MAX_PER_SPACE + " snapshots. Delete an older one from Snapshots first.");
        }
        String name = label == null || label.isBlank() ? "Saved from chat" : label;
        var snap = snapshotService.save(current, name);
        return plain("Saved a snapshot called \"" + snap.getLabel() + "\" with " + snap.getThoughtCount() + " thoughts and "
                + snap.getLinkCount() + " connections. You can restore it any time from Snapshots.");
    }

    // ------------------------------------------------------------------ small helpers

    private static String pair(Long a, Long b) {
        return Math.min(a, b) + "-" + Math.max(a, b);
    }

    private static String title(Thought t) {
        if (t.getTitle() != null && !t.getTitle().isBlank()) return t.getTitle();
        String x = t.getText() == null ? "" : t.getText();
        return x.length() > 24 ? x.substring(0, 24) + "…" : x;
    }

    /** Exact name first, then a loose "contains" match. */
    private static IdeaSpace findSpace(List<IdeaSpace> all, String name, IdeaSpace exclude) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim().toLowerCase();
        List<IdeaSpace> pool = all.stream().filter(s -> exclude == null || !s.getId().equals(exclude.getId())).toList();
        for (IdeaSpace s : pool) if (String.valueOf(s.getName()).equalsIgnoreCase(n)) return s;
        for (IdeaSpace s : pool) {
            String sn = String.valueOf(s.getName()).toLowerCase();
            if (sn.contains(n) || n.contains(sn)) return s;
        }
        return null;
    }
}