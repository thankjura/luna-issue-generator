package ru.slie.luna.rest.client.generator.commands.utils;

import net.datafaker.Faker;

import java.util.*;

public class IssueGenerator {
    private final Faker fakerRU = new Faker(Locale.of("ru"));
    private final List<ProjectGenParams> params;
    private final Random random = new Random();

    public IssueGenerator(List<ProjectGenParams> params) {
        this.params = params;
    }

    private Map<String, String> getProjectParam(String projectKey) {
        Map<String, String> result = new HashMap<>();
        result.put("key", projectKey);
        return result;
    }

    private Map<String, String> getUserParam(String username) {
        Map<String, String> result = new HashMap<>();
        result.put("name", username);
        return result;
    }

    private Map<String, String> getIdParam(String id) {
        Map<String, String> result = new HashMap<>();
        result.put("id", id);
        return result;
    }

    public Map<String, Object> genIssue() {
        ProjectGenParams data = params.get(random.nextInt(params.size()));
        Map<String, Object> issueRequest = new HashMap<>();
        issueRequest.put("project", getProjectParam(data.getProjectKey()));
        issueRequest.put("issuetype", getIdParam(data.getIssueTypes().get(random.nextInt(data.getIssueTypes().size()))));
        issueRequest.put("summary", generateSummary());
        if (!data.getPriorities().isEmpty()) {
            issueRequest.put("priority", getIdParam(data.getPriorities().get(random.nextInt(data.getPriorities().size()))));
        }

        if (random.nextInt(100) < 10) {
            issueRequest.put("description", generateDescriptionMarkdown());
        } else {
            issueRequest.put("description", String.join("\n\n", fakerRU.lorem().paragraphs(random.nextInt(2, 5))));
        }

        issueRequest.put("reporter", getUserParam(data.getUsers().get(random.nextInt(data.getUsers().size()))));
        issueRequest.put("assignee", getUserParam(data.getUsers().get(random.nextInt(data.getUsers().size()))));

        return issueRequest;
    }

    public String generateSummary() {
        String title = fakerRU.lorem().sentence(fakerRU.random().nextInt(3, 6));
        return title.substring(0, Math.min(title.length(), 255));
    }

    public String generateDescriptionMarkdown() {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(fakerRU.lorem().sentence(2)).append("\n\n");

        String word = fakerRU.lorem().word();
        String paragraph = fakerRU.lorem().paragraph(2);
        sb.append(paragraph).append(" **").append(word).append("**.\n\n");

        sb.append("## Детали:\n");
        int stepsCount = fakerRU.random().nextInt(3, 5);
        for (int i = 1; i <= stepsCount; i++) {
            sb.append("* ").append(fakerRU.lorem().sentence(3)).append("\n");
        }

        sb.append("\n```json\n");
        sb.append("{\n");
        sb.append("  \"status\": \"error\",\n");
        sb.append("  \"code\": ").append(fakerRU.random().nextInt(400, 500)).append(",\n");
        sb.append("  \"message\": \"").append(fakerRU.lorem().word()).append(" exception\"\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }
}
