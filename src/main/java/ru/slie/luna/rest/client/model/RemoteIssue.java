package ru.slie.luna.rest.client.model;

public class RemoteIssue {
    private Long id;
    private String key;
    private String summary;
    private String description;
    private RemoteUser creator;
    private RemoteUser author;
    private RemoteUser assignee;
    private String created;
    private RemoteStatus status;
    private RemoteProject project;
    private RemoteIssueType issueType;

    public Long getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getSummary() {
        return summary;
    }

    public String getDescription() {
        return description;
    }

    public RemoteUser getCreator() {
        return creator;
    }

    public RemoteUser getAuthor() {
        return author;
    }

    public RemoteUser getAssignee() {
        return assignee;
    }

    public String getCreated() {
        return created;
    }

    public RemoteStatus getStatus() {
        return status;
    }

    public RemoteProject getProject() {
        return project;
    }

    public RemoteIssueType getIssueType() {
        return issueType;
    }
}
