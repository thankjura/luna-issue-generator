package ru.slie.luna.rest.client;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.slie.luna.rest.client.model.*;
import ru.slie.luna.rest.client.model.request.AddRemoveRequest;
import ru.slie.luna.rest.client.model.request.RequestUser;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public class LunaRestClient {
    private final RestClient restClient;

    public LunaRestClient(String baseUrl, String username, String password) {
        String credentials = username + ":" + password;
        String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        String basicAuthHeader = "Basic " + base64Credentials;

        BasicCookieStore cookieStore = new BasicCookieStore();
        RequestConfig requestConfig = RequestConfig.custom()
                                              .setConnectionRequestTimeout(Timeout.ofMilliseconds(5000))
                                              .setResponseTimeout(Timeout.ofMilliseconds(10000))
                                              .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                                                 .setDefaultCookieStore(cookieStore)
                                                 .setDefaultRequestConfig(requestConfig)
                                                 .build();

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        this.restClient = RestClient.builder().requestFactory(requestFactory).baseUrl(baseUrl).defaultHeader("Authorization", basicAuthHeader).build();
    }

    public RestClient get() {
        return restClient;
    }

    public RemoteSearchResult<RemoteProject> findProjects(int page, int limit) {
        return restClient.get().uri("/rest/projects?limit={limit}&page={page}", limit, page).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteProjectWithSchemas getProject(String key) {
        return restClient.get().uri("/rest/projects/{limit}/schemas", key).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteSearchResult<RemoteUser> findUsers() {
        return findUsers(1, 10);
    }

    public RemoteSearchResult<RemoteUser> findUsers(int page, int limit) {
        return restClient.get().uri("/rest/users?limit={limit}&page={page}", limit, page).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteSearchResult<RemoteUser> findUsersForProject(String projectKey, int page, int limit) {
        return restClient.get().uri("/rest/users/assignable?projectKey={projectKey}&limit={limit}&page={page}", projectKey, limit, page).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteUser createUser(RequestUser user) {
        return restClient.post().uri("/rest/users/create", user).body(user).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteUser addUserToGroups(String login, List<String> groups) {
        AddRemoveRequest<String> body = new AddRemoveRequest<>();
        body.setAdd(groups);
        return restClient.put().uri("/rest/users/{login}/groups", login).body(body).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteIssue createIssue(Map<String, Object> request) {
        return restClient.post().uri("/rest/issues/create", request).body(request).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public Long countIssues() {
        return restClient.get().uri("/rest/issues/count").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteStatisticResult getStatisticReport(String fieldId) {
        return restClient.get().uri("/rest/report/statistic/group?fieldId={fieldId}", fieldId).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public List<RemotePriority> getPriorities() {
        return restClient.get().uri("/rest/priorities/all").retrieve().body(new ParameterizedTypeReference<>() {});
    }
}
