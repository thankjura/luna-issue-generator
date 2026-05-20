package ru.slie.luna.rest.client.model;

import java.util.List;

public class RemotePrioritySchema {
    private Long id;
    private String name;
    private List<String> optionIds;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getOptionIds() {
        return optionIds;
    }
}
