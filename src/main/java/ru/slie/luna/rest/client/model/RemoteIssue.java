package ru.slie.luna.rest.client.model;

public class RemoteIssue {
    private String id;
    private String key;
    private Fields fields;

    public String getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public Fields getFields() {
        return fields;
    }

    public static class Fields {
        private RemoteProject project;
        private RemoteIssueType issuetype;
        private RemoteUser reporter;
        private RemoteUser assignee;
        private RemoteStatus status;
        private String summary;
        private String description;
        private RemotePriority priority;
        private String created;
    }
}
