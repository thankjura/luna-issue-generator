package ru.slie.luna.rest.client.model;

public class RemoteUser {
    private String key;
    private String name;
    private String displayName;
    private Boolean active;

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public Boolean getActive() {
        return active;
    }

    public String getDisplayName() {
        return displayName;
    }
}
