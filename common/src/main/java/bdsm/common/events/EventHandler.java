package bdsm.common.events;

public interface EventHandler<T> {
    void handle(T event, Publisher publisher) throws Exception;
}
