package ru.slie.luna.rest.client.model;

import java.util.List;

public class RemoteSearchIssuesResult<T> {
    private Integer maxResults;
    private Integer total;
    private List<RemoteIssue> issues;

    public Integer getMaxResults() {
        return maxResults;
    }

    public Integer getTotal() {
        return total;
    }

    public List<RemoteIssue> getIssues() {
        return issues;
    }
}
