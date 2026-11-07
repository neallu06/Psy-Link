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
import com.fallhack.psy_link.service.SpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Psy, the chat companion. Nothing here runs unless the user's message asks for it:
 * the model first classifies the message, and anything that is not a clear request is plain conversation.
 * Suggestions (links, categories) are returned as "actions" the user must accept in the UI.
 */
@RestController
@RequestMapping("/api/psy")
public class PsyController {

    public record PsyRequest(String message, Long spaceId, List<GeminiService.Turn> history) {}
    public record Action(String kind, Long fromId, Long toId, Long typeId, String typeName,
                         String fromTitle, String toTitle, String reason,
                         Long thoughtId, String category) {}
    public record PsyReply(String reply, List<Action> actions, Long openSpaceId, String openSpaceName, boolean spacesChanged) {}

    private final UserRepository users;
    private final IdeaSpaceRepository spaces;
    private final ThoughtRepository thoughts;
    private final ThoughtLinkRepository links;
    private final LinkTypeRepository types;
    private final ThoughtVersionRepository versions;
    private final GeminiService gemini;
    private final SpaceService spaceService;

    public PsyController(UserRepository users, IdeaSpaceRepository spaces, ThoughtRepository thoughts,
                         ThoughtLinkRepository links, LinkTypeRepository types, ThoughtVersionRepository versions,
                         GeminiService gemini, SpaceService spaceService) {
        this.users = users;
        this.spaces = spaces;
        this.thoughts = thoughts;
        this.links = links;
        this.types = types;
        this.versions = versions;
        this.gemini = gemini;
        this.spaceService = spaceService;
    }

    private static PsyReply plain(String text) {
        return new PsyReply(text, List.of(), null, null, false);
    }

    @PostMapping
    public PsyReply talk(Principal p, @RequestBody PsyRequest body) {
        AppUser u = users.findByUsername(p.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        String msg = body.message() == null ? "" : body.message().trim();
        if (msg.isEmpty()) return plain("I'm listening. What's on your mind?");

        List<GeminiService.Turn> history = body.history() == null ? List.of() : body.history();
        List<IdeaSpace> mine = spaces.findByOwnerId(u.getId());
        IdeaSpace current = body.spaceId() == null ? null
                : mine.stream().filter(s -> s.getId().equals(body.spaceId())).findFirst().orElse(null);

        try {
            GeminiService.Intent intent = gemini.route(msg, history,
                    mine.stream().map(IdeaSpace::getName).toList(),
                    current == null ? "(none, the user is on the home page)" : current.getName());
            return switch (intent.kind()) {
                case "LINKS" -> doLinks(u, current);
                case "CATEGORIES" -> doCategories(current);
                case "ANALYZE" -> doAnalyze(mine, current, intent.arg(0), null, msg, history);
                case "PERSPECTIVE" -> doAnalyze(mine, current, intent.arg(1), intent.arg(0), msg, history);
                case "MERGE" -> doMerge(u, mine, intent.arg(0), intent.arg(1), intent.arg(2));
                case "HISTORY" -> doHistory(current, intent.arg(0));
                default -> doChat(current, msg, history);
            };
        } catch (Exception e) {
            return plain("I couldn't reach my thinking cap just now. Please try again in a moment.");
        }
    }

    // ------------------------------------------------------------------ actions

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
            actions.add(new Action("link", sl.fromId(), sl.toId(), lt.getId(), lt.getName(),
                    title(byId.get(sl.fromId())), title(byId.get(sl.toId())), sl.reason(), null, null));
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
            actions.add(new Action("category", null, null, null, null, title(t), null, null, t.getId(), sc.category()));
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
