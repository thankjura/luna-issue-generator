package ru.slie.luna.rest.client.model;

public class RemoteStatisticGroup {
    private String id;
    private String label;
    private Long count;
    private String qs;

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public Long getCount() {
        return count;
    }

    public String getQs() {
        return qs;
    }
}
