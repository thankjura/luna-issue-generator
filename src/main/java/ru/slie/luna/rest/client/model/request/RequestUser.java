package ru.slie.luna.rest.client.model.request;

public class RequestUser {
    private final int directoryId;
    private final String login;
    private final String email;
    private final String name;
    private final String lastName;

    public RequestUser(int directoryId, String login, String email, String name, String lastName) {
        this.directoryId = directoryId;
        this.login = login;
        this.email = email;
        this.name = name;
        this.lastName = lastName;
    }

    public int getDirectoryId() {
        return directoryId;
    }

    public String getLogin() {
        return login;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getLastName() {
        return lastName;
    }
}
