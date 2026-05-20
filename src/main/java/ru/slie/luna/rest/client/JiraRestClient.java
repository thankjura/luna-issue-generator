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

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JiraRestClient {
    private final RestClient restClient;

    public JiraRestClient(String baseUrl, String username, String password) {
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

    public List<RemoteProject> getProjects() {
        return restClient.get().uri("/rest/api/2/project").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteProject getProject(String key) {
        return restClient.get().uri("/rest/api/2/project/{key}", key).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemotePrioritySchema getProjectPrioritySchema(String projectKey) {
        return restClient.get().uri("/rest/api/2/project/{key}/priorityscheme", projectKey).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public List<RemoteUser> findUsersForProject(String projectKey, int page, int limit) {
        int startAt = page * limit;
        return restClient.get().uri("/rest/api/2/user/assignable/search?project={projectKey}&maxResults={limit}&startAt={startAt}", projectKey, limit, startAt).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public RemoteIssue createIssue(Map<String, Object> request) {
        Map<String, Object> body = new HashMap<>();
        body.put("fields", request);
        return restClient.post().uri("/rest/api/2/issue").body(body).retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public List<RemotePriority> getPriorities() {
        return restClient.get().uri("/rest/api/2/priority").retrieve().body(new ParameterizedTypeReference<>() {});
    }
}
