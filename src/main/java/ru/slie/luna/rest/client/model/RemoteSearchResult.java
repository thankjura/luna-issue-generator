package ru.slie.luna.rest.client.model;

import java.util.List;

public class RemoteSearchResult<T> {
    private Integer limit;
    private Integer total;
    private Integer page;
    private List<T> results;

    public Integer getLimit() {
        return limit;
    }

    public Integer getTotal() {
        return total;
    }

    public Integer getPage() {
        return page;
    }

    public List<T> getResults() {
        return results;
    }
}
