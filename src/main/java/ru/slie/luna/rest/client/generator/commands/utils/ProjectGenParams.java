package ru.slie.luna.rest.client.generator.commands.utils;

import java.util.*;

public class ProjectGenParams {
    private final String projectKey;
    private final List<String> issueTypes;
    private final List<String> users;
    private final List<String> priorities;

    public ProjectGenParams(String projectKey) {
        this.projectKey = projectKey;
        this.issueTypes = new ArrayList<>();
        this.users = new ArrayList<>();
        this.priorities = new ArrayList<>();
    }

    public List<String> getIssueTypes() {
        return issueTypes;
    }
    public List<String> getUsers() {
        return users;
    }

    public void addIssueTypes(List<String> issueTypeIds) {
        issueTypes.addAll(issueTypeIds);
    }

    public void addUser(String login) {
        users.add(login);
    }

    public void addPriorities(Collection<String> priorities) {
        this.priorities.addAll(priorities);
    }

    public String getProjectKey() {
        return projectKey;
    }

    public List<String> getPriorities() {
        return priorities;
    }
}
