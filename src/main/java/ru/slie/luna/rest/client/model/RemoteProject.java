package ru.slie.luna.rest.client.model;

import java.util.List;

public class RemoteProject {
    private String id;
    private String key;
    private String name;
    private String description;
    private List<RemoteIssueType> issueTypes;

    public String getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<RemoteIssueType> getIssueTypes() {
        return issueTypes;
    }
}
