package ru.slie.luna.rest.client.model;

public class RemoteProjectWithSchemas extends RemoteProject {
    private RemoteIssueTypeSchema issueTypeSchema;
    private RemotePrioritySchema prioritySchema;

    public RemoteIssueTypeSchema getIssueTypeSchema() {
        return issueTypeSchema;
    }

    public RemotePrioritySchema getPrioritySchema() {
        return prioritySchema;
    }
}
