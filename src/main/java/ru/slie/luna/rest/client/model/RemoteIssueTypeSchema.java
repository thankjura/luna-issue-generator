package ru.slie.luna.rest.client.model;

import java.util.List;

public class RemoteIssueTypeSchema {
    private Long id;
    private String name;
    private List<RemoteIssueType> issueTypes;
    private List<Long> issueTypeIds;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<RemoteIssueType> getIssueTypes() {
        return issueTypes;
    }

    public List<Long> getIssueTypeIds() {
        return issueTypeIds;
    }
}
