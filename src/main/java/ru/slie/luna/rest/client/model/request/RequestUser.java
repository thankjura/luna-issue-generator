package ru.slie.luna.rest.client.model.request;

import java.util.Arrays;
import java.util.List;

public class RequestUser {
    private final String name;
    private final String emailAddress;
    private final String displayName;
    private final List<String> applicationKeys = Arrays.asList("jira-software", "jira-core");

    public RequestUser(String name, String emailAddress, String displayName) {
        this.name = name;
        this.emailAddress = emailAddress;
        this.displayName = displayName;
    }

    public String getName() {
        return name;
    }
    public String getEmailAddress() {
        return emailAddress;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getApplicationKeys() {
        return applicationKeys;
    }
}
