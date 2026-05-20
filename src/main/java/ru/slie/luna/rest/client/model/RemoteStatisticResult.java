package ru.slie.luna.rest.client.model;

import java.util.List;

public class RemoteStatisticResult {
    private RemoteIssueField issueField;
    private List<RemoteStatisticGroup> groups;
    private List<String> keys;
    private Long totalCount;

    public RemoteIssueField getIssueField() {
        return issueField;
    }

    public List<RemoteStatisticGroup> getGroups() {
        return groups;
    }

    public List<String> getKeys() {
        return keys;
    }

    public Long getTotalCount() {
        return totalCount;
    }
}
