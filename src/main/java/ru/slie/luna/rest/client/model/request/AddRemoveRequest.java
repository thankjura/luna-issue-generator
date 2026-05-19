package ru.slie.luna.rest.client.model.request;

import java.util.Collection;

public class AddRemoveRequest<E> {
    private Collection<E> add;
    private Collection<E> remove;

    public Collection<E> getAdd() {
        return add;
    }

    public void setAdd(Collection<E> add) {
        this.add = add;
    }

    public Collection<E> getRemove() {
        return remove;
    }

    public void setRemove(Collection<E> remove) {
        this.remove = remove;
    }
}
