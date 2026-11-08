package com.fallhack.psy_link.service;

import com.fallhack.psy_link.model.Thought;
import com.fallhack.psy_link.model.ThoughtVersion;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    public record Analysis(String title, String category, String tags, String summary) {}
    public record SuggestedLink(Long fromId, Long toId, String typeKey, String reason) {}
    public record SuggestedCategory(Long thoughtId, String category) {}
    public record SearchHit(Long thoughtId, String reason) {}
    public record Bridge(Long aId, Long bId, String reason) {}
    public record Turn(String role, String text) {}
    public record Intent(String kind, List<String> args) {
        public String arg(int i) { return i < args.size() ? args.get(i) : ""; }
    }

    private static final Set<String> KINDS = Set.of("CHAT", "LINKS", "CATEGORIES", "ANALYZE", "PERSPECTIVE",
            "MERGE", "HISTORY", "SEARCH", "PATTERNS", "PROMPTS", "BRIDGES", "SNAPSHOT");

    /**
     * Words that can signal a request for one of Psy's actions. Ordinary conversation almost never contains
     * them, so it skips the routing call entirely: one Gemini call per chat message instead of two.
     */
    private static final List<String> ACTION_WORDS = List.of(
            "connect", "link", "relat", "categor", "analy", "summar", "perspective", "viewpoint", "point of view",
            "lens", "merge", "combine", "history", "develop", "evolv", "changed", "search", "find", "look for",
            "where did i", "pattern", "how do i think", "my thinking", "question", "prompt", "explor", "bridge",
            "snapshot", "theme", "suggest", "between");

    private static final Pattern LINK_LINE =
            Pattern.compile("(\\d+)\\s*-\\s*(\\d+)\\s*\\|\\s*(\\w+)\\s*\\|\\s*(.+)");
    private static final Pattern CATEGORY_LINE =
            Pattern.compile("(\\d+)\\s*\\|\\s*(.+)");

    /** Psy's personality. Every conversational prompt starts with this. */
    private static final String PSY = """
            You are Psy, a warm, curious companion inside Psy-Link, an app where people capture and connect their own thoughts.
            Your role is to encourage the user to explore and write down their OWN ideas. Never take over:
            do not rewrite, replace or "improve" their thoughts unless asked, do not present your ideas as the answer,
            and do not analyze, connect, categorize, merge or create anything the user has not asked for.
            Keep replies friendly and short. When it fits, end with one gentle question that invites the user to keep thinking.
            Plain text only: no markdown headings, no bold. Simple hyphen lists are fine.
            """;

    private final Client client;
    private final String model;
    private final String fallbackModel;

    public GeminiService(Client client, @Value("${gemini.model}") String model,
                         @Value("${gemini.fallback-model:gemini-3.5-flash-lite}") String fallbackModel) {
        this.client = client;
        this.model = model;
        this.fallbackModel = fallbackModel;
    }

    /** Calls Gemini. If the main model is rate-limited or overloaded, retries once on the fallback model. */
    private String call(String prompt) {
        try {
            return generate(model, prompt);
        } catch (RuntimeException e) {
            boolean retryable = isRateLimited(e) || isOverloaded(e);
            log.warn("Gemini call failed on {} ({}){}", model, e.getMessage(), retryable ? "; trying " + fallbackModel : "");
            if (!retryable || fallbackModel == null || fallbackModel.isBlank() || fallbackModel.equals(model)) throw e;
            return generate(fallbackModel, prompt);
        }
    }

    private String generate(String modelName, String prompt) {
        GenerateContentResponse response = client.models.generateContent(modelName, prompt, null);
        String text = response.text();
        return text == null ? "" : text.trim();
    }

    // ------------------------------------------------------------------ error diagnosis

    private static String allMessages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 2000; t = t.getCause()) sb.append(t.getMessage()).append(' ');
        return sb.toString().toLowerCase(Locale.ROOT);
    }
    static boolean isRateLimited(Throwable e) {
        String m = allMessages(e);
        return m.contains("429") || m.contains("resource_exhausted") || m.contains("quota") || m.contains("rate limit");
    }
    static boolean isOverloaded(Throwable e) {
        String m = allMessages(e);
        return m.contains("503") || m.contains("unavailable") || m.contains("overloaded");
    }

    /** A message that tells the user what is actually wrong, instead of one generic line for every failure. */
    public static String friendlyError(Throwable e) {
        String m = allMessages(e);
        if (isRateLimited(e)) return "I've reached my usage limit for the moment. Give me a minute and I will be back with your amazing ideas!";
        if (isOverloaded(e)) return "My thoughts are overloaded right now. Let's try again in a moment..";
        if (m.contains("api key") || m.contains("401") || m.contains("403") || m.contains("permission_denied") || m.contains("unauthenticated"))
            return "I can't reach with my thoughts right now. Let's take a small break? If it keeps happening, please contact the dev to help me.";
        if (m.contains("404") || m.contains("not found") || m.contains("not supported"))
            return "My thoughts aren't coming to me right now. Psy needs to rest and will be back soon to go over your ideas with you!";
        return "Uh-oh, Psy is not feeling well right now. Please contact the dev to cure me.";
    }

    /** True if the message might be asking for one of Psy's actions (so the router model is worth a call). */
    public static boolean mayBeRequest(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        for (String w : ACTION_WORDS) if (m.contains(w)) return true;
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String listThoughts(List<Thought> thoughts, int limit) {
        StringBuilder sb = new StringBuilder();
        for (Thought t : thoughts.stream().limit(limit).toList()) {
            sb.append("- [").append(t.getId()).append("] ");
            if (t.getTitle() != null && !t.getTitle().isBlank()) sb.append(t.getTitle()).append(": ");
            sb.append(clip(t.getText(), 500)).append('\n');
        }
        return sb.length() == 0 ? "(none)\n" : sb.toString();
    }

    private static String transcript(List<Turn> history) {
        if (history == null || history.isEmpty()) return "(no earlier messages)\n";
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, history.size() - 8);
        for (Turn t : history.subList(from, history.size())) {
            sb.append("psy".equalsIgnoreCase(t.role()) ? "Psy: " : "User: ").append(clip(t.text(), 600)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ thought titling (on add)

    /** Titles and categorizes a thought. A plain line format avoids needing a JSON parser. */
    public Analysis analyze(String thought) {
        String reply = call("""
                You organize a person's raw thoughts. Analyze the thought below.
                Reply in EXACTLY this format and nothing else:
                TITLE: <a main topic of at most 5 words>
                CATEGORY: <one or two words, e.g. Work, Health, Creativity, Relationships, Learning, Question>
                TAGS: <3 to 5 short lowercase tags separated by commas>
                SUMMARY: <one short sentence>

                Thought: %s
                """.formatted(thought));

        String title = "", category = "Uncategorized", tags = "", summary = "";
        for (String line : reply.split("\\R")) {
            if (line.startsWith("TITLE:")) title = line.substring(6).trim();
            else if (line.startsWith("CATEGORY:")) category = line.substring(9).trim();
            else if (line.startsWith("TAGS:")) tags = line.substring(5).trim();
            else if (line.startsWith("SUMMARY:")) summary = line.substring(8).trim();
        }
        return new Analysis(title, category, tags, summary);
    }

    // ------------------------------------------------------------------ Psy: intent routing

    /** Decides whether the user's latest message explicitly asks for one of Psy's actions. */
    public Intent route(String message, List<Turn> history, List<String> spaceNames, String currentSpace) {
        // Plain conversation never needs the router: skip the extra call.
        if (!mayBeRequest(message)) return new Intent("CHAT", List.of());

        String reply = call("""
                You are a router for a thinking app. Decide whether the user's LATEST message explicitly asks for one of the actions below.
                Reply with EXACTLY one line and nothing else, using one of these forms:
                CHAT
                LINKS
                CATEGORIES
                ANALYZE | <space name, or empty for the current space>
                PERSPECTIVE | <the viewpoint requested> | <space name, or empty for the current space>
                MERGE | <first space name> | <second space name> | <name for the new space, or empty>
                HISTORY | <title or key words of the thought>
                SEARCH | <what to look for, in the user's own words>
                PATTERNS
                PROMPTS | <space name, or empty for the current space>
                BRIDGES | <first space name, or empty for the current space> | <second space name, or empty for all other spaces>
                SNAPSHOT | <short name for the snapshot, or empty>

                Meaning:
                LINKS = find, suggest or discover relationships/connections between thoughts.
                CATEGORIES = suggest categories, groups or labels for thoughts.
                ANALYZE = analyze or summarize the themes of a whole idea space.
                PERSPECTIVE = look at ideas through a specific viewpoint (skeptic, investor, child, ...).
                MERGE = combine two idea spaces into a new one.
                HISTORY = how a thought has developed or changed over time.
                SEARCH = find thoughts by meaning across all idea spaces ("where did I write about...", "find my thoughts on...").
                PATTERNS = analyze the user's own thinking habits and patterns across everything they have written.
                PROMPTS = generate questions or exploration prompts to help the user think further.
                BRIDGES = find connections between idea spaces that seem unrelated.
                SNAPSHOT = save a snapshot of the current idea space's graph.
                If the user is just chatting, sharing an idea, asking a general question, or is not clearly asking for one of these, answer CHAT.

                Current space: %s
                All spaces: %s
                Recent conversation:
                %s
                Latest message: %s
                """.formatted(currentSpace, String.join(", ", spaceNames), transcript(history), message));

        for (String line : reply.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", -1);
            String kind = parts[0].trim().toUpperCase().replaceAll("[^A-Z]", "");
            if (!KINDS.contains(kind)) break;
            List<String> args = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) args.add(parts[i].trim());
            return new Intent(kind, args);
        }
        return new Intent("CHAT", List.of());
    }

    // ------------------------------------------------------------------ Psy: requested actions

    /** Suggests typed relationships between thoughts. */
    public List<SuggestedLink> suggestLinks(List<Thought> thoughts) {
        List<SuggestedLink> result = new ArrayList<>();
        if (thoughts.size() < 2) return result;

        StringBuilder list = new StringBuilder();
        for (Thought t : thoughts.stream().limit(60).toList()) {
            list.append(t.getId()).append(": ").append(clip(t.getText(), 400)).append('\n');
        }
        String reply = call("""
                Below are numbered thoughts. Suggest up to 8 meaningful relationships between pairs.
                Output one per line in EXACTLY this format and nothing else:
                <idA>-<idB> | <type> | <short reason, max 8 words>

                <type> must be one of:
                related     (A and B share a subject)
                supports    (A provides evidence for B)
                contradicts (A and B have opposing perspectives)
                develops    (A leads to B)
                part        (A belongs to the larger idea B)

                Use only the ids given. Skip weak or obvious connections.

                %s
                """.formatted(list));

        for (String line : reply.split("\\R")) {
            Matcher m = LINK_LINE.matcher(line.trim());
            if (m.matches()) {
                result.add(new SuggestedLink(Long.valueOf(m.group(1)), Long.valueOf(m.group(2)),
                        m.group(3).toLowerCase(), m.group(4).trim()));
            }
        }
        return result;
    }

    /** Suggests a category per thought (one or two words). */
    public List<SuggestedCategory> suggestCategories(List<Thought> thoughts) {
        List<SuggestedCategory> result = new ArrayList<>();
        if (thoughts.isEmpty()) return result;

        StringBuilder list = new StringBuilder();
        for (Thought t : thoughts.stream().limit(60).toList()) {
            list.append(t.getId()).append(" (now: ").append(t.getCategory() == null ? "none" : t.getCategory())
                    .append("): ").append(clip(t.getText(), 400)).append('\n');
        }
        String reply = call("""
                Suggest a clear category (one or two words) for each numbered thought below.
                Reuse the same category name for thoughts that belong together, and keep the total number of categories small.
                Output one per line in EXACTLY this format and nothing else:
                <id> | <Category>

                %s
                """.formatted(list));

        for (String line : reply.split("\\R")) {
            Matcher m = CATEGORY_LINE.matcher(line.trim());
            if (m.matches()) {
                result.add(new SuggestedCategory(Long.valueOf(m.group(1)), clip(m.group(2).trim(), 30)));
            }
        }
        return result;
    }

    /** Analyzes a whole Idea Space, optionally through a specific perspective. */
    public String analyzeSpace(String spaceName, List<Thought> thoughts, String lens, String message, List<Turn> history) {
        String task = (lens == null || lens.isBlank())
                ? "Analyze the whole space: name the main themes, point out any tensions or gaps you notice, and finish with one question that helps the user keep developing their own ideas."
                : "Look at the whole space through this perspective: " + lens + ". Say what stands out from that viewpoint, and finish with one question that helps the user keep developing their own ideas.";
        return call("""
                %s
                The user asked about their idea space "%s". Its thoughts:
                %s
                %s
                Stay grounded in the user's own words and do not invent thoughts. Use under 220 words.

                Conversation so far:
                %s
                User's request: %s
                """.formatted(PSY, spaceName, listThoughts(thoughts, 60), task, transcript(history), message));
    }

    /** Narrates how one thought has developed, from its saved versions (oldest first). */
    public String describeHistory(Thought thought, List<ThoughtVersion> versionsOldestFirst) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (ThoughtVersion v : versionsOldestFirst) {
            sb.append("Version ").append(n++).append(" (").append(v.getNote()).append(", ").append(v.getCreatedAt()).append("): ");
            if (v.getTitle() != null) sb.append(v.getTitle()).append(" - ");
            sb.append(clip(v.getText(), 600)).append('\n');
        }
        return call("""
                %s
                Here is the development history of one of the user's thoughts, oldest first:
                %s
                In under 120 words, describe how the thought has developed: what shifted, what stayed constant.
                Then ask one reflective question about where it might go next.
                """.formatted(PSY, sb));
    }

    /** After a merge: what do the two spaces have in common and what might grow from them. */
    public String synthesizeMerge(String nameA, List<Thought> a, String nameB, List<Thought> b) {
        return call("""
                %s
                The user just merged two idea spaces into a new one.
                Space "%s":
                %s
                Space "%s":
                %s
                In under 150 words: name two or three overlapping or complementary themes, then offer one or two
                possible new concepts that could emerge, framed as possibilities for the user to shape (not conclusions).
                End with one question inviting the user to add their own thought to the merged space.
                """.formatted(PSY, nameA, listThoughts(a, 30), nameB, listThoughts(b, 30)));
    }

    /** Everyday conversation with Psy. Context is only background; Psy does not volunteer analysis. */
    public String converse(String message, List<Turn> history, String spaceName, List<Thought> context) {
        String where = spaceName == null
                ? "The user is on the home page and has no space open."
                : "The user has the idea space \"" + spaceName + "\" open. Its thoughts, for background only:\n" + listThoughts(context, 40);
        String reply = call("""
                %s
                %s
                Only use the background if the user's message is about their thoughts. Do not summarize or analyze it unprompted.
                Hold a warm, natural conversation: respond to what they actually said, encourage them, and when it fits
                invite them to write a new idea of their own. Do not offer to do tasks unless they ask.
                Keep it under 120 words.

                Conversation so far:
                %s
                User: %s
                """.formatted(PSY, where, transcript(history), message));
        return reply.isBlank() ? "I'm here and listening. What's on your mind?" : reply;
    }

    // ------------------------------------------------------------------ v3: semantic search

    /**
     * Finds thoughts by meaning. Gemini is shown a compact list of the user's thoughts and picks the ones that
     * match the query, even when the words differ. Capped to the candidates passed in, so it suits a personal
     * collection; a vector index would be the next step for very large ones.
     */
    public List<SearchHit> search(String query, List<Thought> pool, Map<Long, String> spaceNames) {
        StringBuilder list = new StringBuilder();
        for (Thought t : pool) {
            list.append('[').append(t.getId()).append("] (").append(spaceNames.getOrDefault(t.getSpaceId(), "?")).append(") ");
            if (t.getTitle() != null && !t.getTitle().isBlank()) list.append(t.getTitle()).append(": ");
            list.append(clip(t.getText(), 220).replace('\n', ' ')).append('\n');
        }
        String reply = call("""
                Find the thoughts below that are most relevant in MEANING to the search, even if the wording differs.
                Return at most 8, best first, one per line in EXACTLY this format and nothing else:
                <id> | <reason in at most 10 words>
                If nothing is relevant, return the single word NONE.

                Search: %s

                Thoughts:
                %s
                """.formatted(query, list));

        List<SearchHit> hits = new ArrayList<>();
        Set<Long> valid = new java.util.HashSet<>();
        pool.forEach(t -> valid.add(t.getId()));
        for (String line : reply.split("\\R")) {
            Matcher m = CATEGORY_LINE.matcher(line.trim().replaceAll("^\\[|\\]", ""));
            if (m.matches()) {
                Long id = Long.valueOf(m.group(1));
                if (valid.contains(id) && hits.stream().noneMatch(h -> h.thoughtId().equals(id))) {
                    hits.add(new SearchHit(id, clip(m.group(2).trim(), 100)));
                }
            }
        }
        return hits;
    }

    // ------------------------------------------------------------------ v3: thinking patterns

    /** Gentle, non-judgmental reflection on how the user thinks, from numbers computed on the server. */
    public String thinkingPatterns(String stats, List<Thought> sample, List<Turn> history, String message) {
        return call("""
                %s
                The user asked about their own thinking patterns. Below are facts counted from everything they have written,
                then a sample of their recent thoughts.
                Share three observations that are clearly supported by the facts (for example what they return to,
                how they structure ideas, how often they revisit or revise). Be warm and curious, never judgmental,
                and do not diagnose or make claims about their personality or mental health. Say when something is
                only a hint because there is little data. End with one question that invites them to reflect.
                Use under 220 words.

                FACTS:
                %s
                SAMPLE:
                %s
                Conversation so far:
                %s
                User's request: %s
                """.formatted(PSY, stats, listThoughts(sample, 30), transcript(history), message));
    }

    // ------------------------------------------------------------------ v3: questions and exploration prompts

    /** Open questions and exploration prompts. With no thoughts to go on, it offers gentle starter prompts. */
    public String explorationPrompts(String spaceName, List<Thought> thoughts, String focus) {
        String basis = thoughts.isEmpty()
                ? "The user has no thoughts yet, so offer five gentle starter prompts that help them begin writing their own ideas."
                : "Their idea space \"" + spaceName + "\" has these thoughts:\n" + listThoughts(thoughts, 40);
        return call("""
                %s
                The user asked for questions or exploration prompts. %s
                %s
                Write exactly 5 numbered open-ended questions or prompts, each under 25 words and each taking a different angle:
                go deeper, challenge an assumption, connect to real life, notice something missing, and a small next step.
                Refer to their own words where you can. Do not answer the questions yourself.
                """.formatted(PSY, basis, focus == null || focus.isBlank() ? "" : "Their request: " + focus));
    }

    // ------------------------------------------------------------------ v3: bridges between unrelated spaces

    /** Pairs a thought in space A with a thought in another space when they genuinely speak to each other. */
    public List<Bridge> bridges(String spaceAName, List<Thought> a, List<Thought> others, Map<Long, String> spaceNames) {
        StringBuilder bl = new StringBuilder();
        for (Thought t : others) {
            bl.append('[').append(t.getId()).append("] (").append(spaceNames.getOrDefault(t.getSpaceId(), "?")).append(") ");
            if (t.getTitle() != null && !t.getTitle().isBlank()) bl.append(t.getTitle()).append(": ");
            bl.append(clip(t.getText(), 200).replace('\n', ' ')).append('\n');
        }
        String reply = call("""
                These idea spaces seem unrelated, but their thoughts may speak to each other.
                Group A is the space "%s". Group B is thoughts from the user's other spaces.
                Find up to 6 genuine, non-obvious connections, each pairing ONE thought from A with ONE thought from B.
                Output one per line in EXACTLY this format and nothing else:
                <A id>-<B id> | <what links them, at most 12 words>
                Use only the ids given. Skip weak connections. If there are none, return the single word NONE.

                GROUP A:
                %s
                GROUP B:
                %s
                """.formatted(spaceAName, listThoughts(a, 40), bl));

        List<Bridge> out = new ArrayList<>();
        Pattern p = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)\\s*\\|\\s*(.+)");
        for (String line : reply.split("\\R")) {
            Matcher m = p.matcher(line.trim());
            if (m.matches()) out.add(new Bridge(Long.valueOf(m.group(1)), Long.valueOf(m.group(2)), clip(m.group(3).trim(), 140)));
        }
        return out;
    }
}