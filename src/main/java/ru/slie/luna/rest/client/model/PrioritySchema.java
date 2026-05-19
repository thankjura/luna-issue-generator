package ru.slie.luna.rest.client.model;

import java.util.List;

public class PrioritySchema {
    private Long id;
    private String name;
    private List<RemotePriority> priorities;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<RemotePriority> getPriorities() {
        return priorities;
    }
}
