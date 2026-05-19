package ru.slie.luna.rest.client.model;

public class RemoteUser {
    private Long id;
    private String displayName;
    private String login;
    private String email;
    private Boolean active;

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLogin() {
        return login;
    }

    public String getEmail() {
        return email;
    }

    public Boolean getActive() {
        return active;
    }
}
