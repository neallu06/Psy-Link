package com.fallhack.psy_link.service;

import com.fallhack.psy_link.model.Thought;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiService {

    public record Analysis(String title, String category, String tags, String summary) {}
    public record SuggestedLink(Long fromId, Long toId, String typeKey, String reason) {}

    private static final Pattern LINK_LINE =
            Pattern.compile("(\\d+)\\s*-\\s*(\\d+)\\s*\\|\\s*(\\w+)\\s*\\|\\s*(.+)");

    private final Client client;
    private final String model;

    public GeminiService(Client client, @Value("${gemini.model}") String model) {
        this.client = client;
        this.model = model;
    }

    private String call(String prompt) {
        GenerateContentResponse response = client.models.generateContent(model, prompt, null);
        String text = response.text();
        return text == null ? "" : text.trim();
    }

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

    /** Suggests typed relationships between thoughts. */
    public List<SuggestedLink> suggestLinks(List<Thought> thoughts) {
        List<SuggestedLink> result = new ArrayList<>();
        if (thoughts.size() < 2) return result;

        StringBuilder list = new StringBuilder();
        for (Thought t : thoughts) {
            list.append(t.getId()).append(": ").append(t.getText()).append('\n');
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

    /** The small chat assistant. */
    public String ask(String question, List<Thought> context) {
        StringBuilder ctx = new StringBuilder();
        for (Thought t : context) {
            ctx.append("- ").append(t.getTitle() == null ? "" : t.getTitle() + ": ").append(t.getText()).append('\n');
        }
        return call("""
                You are a thoughtful, concise thinking partner inside an app called ThoughtSpace.
                Here are the user's relevant thoughts:
                %s
                Answer their question. Offer alternative perspectives or unexpected connections
                where useful. Keep it under 150 words.

                Question: %s
                """.formatted(ctx.length() == 0 ? "(none)\n" : ctx, question));
    }
}