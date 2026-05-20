package ru.slie.luna.rest.client.generator.commands.utils;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProjectGenParams {
    private final Set<Long> issueTypes;
    private final Set<Long> users;
    private final Set<Long> priorities;

    public ProjectGenParams() {
        this.issueTypes = new HashSet<>();
        this.users = new HashSet<>();
        this.priorities = new HashSet<>();
    }

    public Set<Long> getIssueTypes() {
        return issueTypes;
    }
    public Set<Long> getUsers() {
        return users;
    }

    public void addIssueTypes(List<Long> issueTypeIds) {
        issueTypes.addAll(issueTypeIds);
    }

    public void addCreatorUser(Long creatorUserId) {
        users.add(creatorUserId);
    }

    public void addPriorities(Collection<Long> priorities) {
        this.priorities.addAll(priorities);
    }
}
