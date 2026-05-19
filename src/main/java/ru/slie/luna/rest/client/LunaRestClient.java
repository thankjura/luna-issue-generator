package ru.slie.luna.rest.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.slie.luna.rest.client.model.RemoteProject;
import ru.slie.luna.rest.client.model.RemoteProjectWithSchemas;
import ru.slie.luna.rest.client.model.RemoteSearchResult;
import ru.slie.luna.rest.client.model.RemoteUser;
import ru.slie.luna.rest.client.model.request.AddRemoveRequest;
import ru.slie.luna.rest.client.model.request.RequestUser;

import java.util.Base64;
import java.util.List;

public class LunaRestClient {
    private final RestClient restClient;
    public LunaRestClient(String baseUrl, String username, String password) {
        String credentials = username + ":" + password;
        String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        String basicAuthHeader = "Basic " + base64Credentials;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);

        this.restClient = RestClient.builder().requestFactory(requestFactory).baseUrl(baseUrl).defaultHeader("Authorization", basicAuthHeader).build();
    }

    public RestClient get() {
        return restClient;
    }

    public RemoteSearchResult<RemoteProject> findProjects(int limit) {
        return restClient.get().uri("/rest/projects?limit={limit}", limit).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteProjectWithSchemas getProject(String key) {
        return restClient.get().uri("/rest/projects/{limit}/schemas", key).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteSearchResult<RemoteUser> getUsers() {
        return restClient.get().uri("/rest/users?limit").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteUser createUser(RequestUser user) {
        return restClient.post().uri("/rest/users/create", user).body(user).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteUser addUserToGroups(String login, List<String> groups) {
        AddRemoveRequest<String> body = new AddRemoveRequest<>();
        body.setAdd(groups);
        return restClient.put().uri("/rest/users/{login}/groups", login).body(body).retrieve().body(new ParameterizedTypeReference<>() {});
    }
}
