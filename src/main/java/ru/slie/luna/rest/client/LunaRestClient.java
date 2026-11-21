package ru.slie.luna.rest.client;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.slie.luna.rest.client.model.*;
import ru.slie.luna.rest.client.model.request.AddRemoveRequest;
import ru.slie.luna.rest.client.model.request.RequestUser;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class LunaRestClient {
    private final RestClient restClient;

    public LunaRestClient(String baseUrl, String username, String password) {
        this(baseUrl, username, password, 20);
    }

    /**
     * @param maxConnections size of the HTTP connection pool, should not be less than the number of worker threads
     *                       (the default pool allows only 5 connections per host)
     */
    public LunaRestClient(String baseUrl, String username, String password, int maxConnections) {
        String credentials = username + ":" + password;
        String basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        BasicCookieStore cookieStore = new BasicCookieStore();
        RequestConfig requestConfig = RequestConfig.custom()
                                                   .setConnectionRequestTimeout(Timeout.ofSeconds(30))
                                                   .setResponseTimeout(Timeout.ofSeconds(120))
                                                   .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(10)).build())
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                                                    .setConnectionManager(connectionManager)
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
        return restClient.get().uri("/rest/projects/{key}/schemas", key).retrieve().body(new ParameterizedTypeReference<>() {});
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
        return restClient.post().uri("/rest/users/create").body(user).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteUser addUserToGroups(String login, List<String> groups) {
        AddRemoveRequest<String> body = new AddRemoveRequest<>();
        body.setAdd(groups);
        return restClient.put().uri("/rest/users/{login}/groups", login).body(body).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteIssue createIssue(Map<String, Object> request) {
        return restClient.post().uri("/rest/issues/create").body(request).retrieve().body(new ParameterizedTypeReference<>() {});
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

    // ---- generic JSON access used by the data generator ----

    public JsonNode getJson(String uri, Object... uriVariables) {
        return restClient.get().uri(uri, uriVariables).retrieve().body(JsonNode.class);
    }

    public JsonNode postJson(String uri, Object body, Object... uriVariables) {
        return restClient.post().uri(uri, uriVariables).body(body).retrieve().body(JsonNode.class);
    }

    public JsonNode putJson(String uri, Object body, Object... uriVariables) {
        return restClient.put().uri(uri, uriVariables).body(body).retrieve().body(JsonNode.class);
    }

    public JsonNode patchJson(String uri, Object body, Object... uriVariables) {
        return restClient.patch().uri(uri, uriVariables).body(body).retrieve().body(JsonNode.class);
    }
}
