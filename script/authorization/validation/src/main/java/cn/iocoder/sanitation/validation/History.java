package cn.iocoder.sanitation.validation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static cn.iocoder.sanitation.validation.Authorization.Window;

final class History {
    record Relationship(String key, String owner, Window window, long version) {}

    private final List<Relationship> revisions = new ArrayList<>();
    private final List<Relationship> active = new ArrayList<>();

    synchronized boolean add(Relationship item) {
        if (active.stream().anyMatch(old -> sameKey(old, item) && old.window().overlaps(item.window()))
                || revisions.stream().anyMatch(old -> sameKey(old, item) && old.version() >= item.version())) {
            return false;
        }
        active.add(item);
        revisions.add(item);
        return true;
    }

    synchronized boolean end(String key, long oldVersion, Instant to, long newVersion) {
        var match = active.stream().filter(old -> old.key().equals(key) && old.version() == oldVersion).findFirst();
        if (match.isEmpty()) return false;
        var old = match.get();
        if (!to.isAfter(old.window().from()) || !to.isBefore(old.window().to())
                || revisions.stream().anyMatch(entry -> entry.key().equals(key) && entry.version() >= newVersion)) {
            return false;
        }
        var ended = new Relationship(key, old.owner(), new Window(old.window().from(), to), newVersion);
        active.set(active.indexOf(old), ended);
        revisions.add(ended);
        return true;
    }

    synchronized Optional<Relationship> resolve(String key, Instant event, boolean timeTrusted) {
        if (!timeTrusted || event == null) return Optional.empty();
        return active.stream().filter(item -> item.key().equals(key) && item.window().contains(event)).findFirst();
    }

    synchronized List<Relationship> revisions() { return List.copyOf(revisions); }

    private static boolean sameKey(Relationship first, Relationship second) {
        return first.key().equals(second.key());
    }
}
