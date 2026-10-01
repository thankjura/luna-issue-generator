package ru.slie.luna.rest.client.model;

public class RemoteIssueType {
    private Long id;
    private String name;
    private boolean subtask = false;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isSubtask() {
        return subtask;
    }
}
