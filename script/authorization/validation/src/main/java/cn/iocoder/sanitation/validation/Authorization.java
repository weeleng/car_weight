package cn.iocoder.sanitation.validation;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** An isolated policy model. All authoritative fixtures are created by the test harness. */
final class Authorization {
    enum Kind { PERSON, SERVICE, DEVICE }
    enum Capability { OPERATE, REGULATE }
    enum TenantType { OPERATOR, REGULATOR, DISPOSAL, MIXED }
    enum Action { VIEW, EDIT, EXPORT, DOWNLOAD, PROCESS_FACT }
    enum Data { OBSERVATION, FILE }
    enum Reason {
        MISSING_IDENTITY, MISSING_SCOPE, UNAVAILABLE, UNTRUSTED, DEVICE_IDENTITY,
        TENANT_INACTIVE, PROJECT_INACTIVE, FUNCTION_DENIED, NO_GRANT,
        EXPIRED, REVOKED, OBJECT_UNAVAILABLE
    }

    record Actor(Kind kind, long id, long home) {}
    record Project(long tenant, long id) {}
    record Window(Instant from, Instant to) {
        Window {
            if (!from.isBefore(to)) throw new IllegalArgumentException("empty or reversed interval");
        }

        boolean contains(Instant instant) {
            return instant != null && !instant.isBefore(from) && instant.isBefore(to);
        }

        boolean overlaps(Window other) {
            return from.isBefore(other.to) && other.from.isBefore(to);
        }
    }

    // No role or authorization result is accepted from a request.
    record Selection(Project project, Data data, Action action, Window dataTime) {}
    record Subject(boolean active, Set<Action> functions) {
        Subject { functions = Set.copyOf(functions); }
    }

    record Tenant(TenantType type, boolean active, Set<Capability> capabilities) {
        Tenant { capabilities = Set.copyOf(capabilities); }
    }

    record Grant(Actor actor, Project project, Data data, Set<Action> projectRole,
                 Window activeTime, Window dataTime, boolean regulatory, boolean revoked) {
        Grant { projectRole = Set.copyOf(projectRole); }
    }

    record Snapshot(Map<Actor, Subject> subjects, Map<Long, Tenant> tenants,
                    Map<Project, Boolean> projects, List<Grant> grants) {
        Snapshot {
            subjects = Map.copyOf(subjects);
            tenants = Map.copyOf(tenants);
            projects = Map.copyOf(projects);
            grants = List.copyOf(grants);
        }
    }

    static final class Authority {
        private Snapshot snapshot;
        private long version = 1;
        private boolean available = true;

        Authority(Snapshot snapshot) { this.snapshot = snapshot; }

        synchronized void change(UnaryOperator<Snapshot> update) {
            snapshot = update.apply(snapshot);
            version++;
        }

        synchronized void available(boolean value) { available = value; }
    }

    static final class Decision {
        private final Actor actor;
        private final Selection selection;
        private final List<Window> windows;
        private final long version;
        private final Instant expires;
        private final Reason reason;

        private Decision(Actor actor, Selection selection, List<Window> windows,
                         long version, Instant expires, Reason reason) {
            this.actor = actor;
            this.selection = selection;
            this.windows = List.copyOf(windows);
            this.version = version;
            this.expires = expires;
            this.reason = reason;
        }

        Actor actor() { return actor; }
        Reason reason() { return reason; }
        long version() { return version; }
        Selection selection() { return selection; }
        List<Window> windows() { return windows; }
        boolean allowed() { return reason == null; }

        boolean covers(Project project, Data data, Action action, Instant eventTime, Instant now) {
            return allowed() && now.isBefore(expires) && selection.project().equals(project)
                    && selection.data() == data && selection.action() == action
                    && selection.dataTime().contains(eventTime)
                    && windows.stream().anyMatch(window -> window.contains(eventTime));
        }
    }

    record Key(Actor actor, Project project, Data data, Action action, Window dataTime, long version) {}

    private final Authority authority;
    private final Clock clock;
    private final Map<Key, Decision> cache = new HashMap<>();

    Authorization(Authority authority, Clock clock) {
        this.authority = authority;
        this.clock = clock;
    }

    Decision decide(Actor actor, Selection selection) {
        synchronized (authority) {
            if (actor == null) return deny(Reason.MISSING_IDENTITY);
            if (selection == null || selection.project() == null || selection.project().tenant() <= 0
                    || selection.project().id() <= 0 || selection.data() == null
                    || selection.action() == null || selection.dataTime() == null) return deny(Reason.MISSING_SCOPE);
            if (!authority.available) return deny(Reason.UNAVAILABLE);
            // All accesses use the authoritative version. The cache stores only low-risk allows.
            long version = authority.version;
            Instant now = clock.instant();
            Key key = key(actor, selection, version);
            Decision previous = cache.get(key);
            if (previous != null && now.isBefore(previous.expires)) return previous;
            Decision result = compute(authority.snapshot, actor, selection, version, now);
            if (result.allowed() && selection.action() == Action.VIEW) cache.put(key, result);
            return result;
        }
    }

    boolean refill(Decision result) {
        synchronized (authority) {
            if (!authority.available || !result.allowed() || result.version != authority.version
                    || result.selection.action() != Action.VIEW || !clock.instant().isBefore(result.expires)) {
                return false;
            }
            cache.put(key(result.actor, result.selection, result.version), result);
            return true;
        }
    }

    private static Key key(Actor actor, Selection request, long version) {
        return new Key(actor, request.project(), request.data(), request.action(), request.dataTime(), version);
    }

    private static Decision compute(Snapshot state, Actor actor, Selection request, long version, Instant now) {
        Subject subject = state.subjects().get(actor);
        if (subject == null || !subject.active()) return deny(Reason.UNTRUSTED);
        if (actor.kind() == Kind.DEVICE) return deny(Reason.DEVICE_IDENTITY);
        Tenant source = state.tenants().get(actor.home());
        Tenant target = state.tenants().get(request.project().tenant());
        if (source == null || target == null || !source.active() || !target.active()) return deny(Reason.TENANT_INACTIVE);
        if (!Boolean.TRUE.equals(state.projects().get(request.project()))) return deny(Reason.PROJECT_INACTIVE);
        if (!subject.functions().contains(request.action())) return deny(Reason.FUNCTION_DENIED);
        boolean cross = actor.home() != request.project().tenant();
        if ((!cross && !source.capabilities().contains(Capability.OPERATE))
                || (cross && (!source.capabilities().contains(Capability.REGULATE)
                || !target.capabilities().contains(Capability.OPERATE)
                || !EnumSet.of(Action.VIEW, Action.EXPORT, Action.DOWNLOAD).contains(request.action())))) {
            return deny(Reason.NO_GRANT);
        }
        var candidates = state.grants().stream().filter(grant -> grant.actor().equals(actor)
                && grant.project().equals(request.project()) && grant.data() == request.data()
                && grant.projectRole().contains(request.action()) && grant.regulatory() == cross
                && grant.dataTime().overlaps(request.dataTime())).toList();
        var valid = candidates.stream().filter(grant -> !grant.revoked() && grant.activeTime().contains(now)).toList();
        if (valid.isEmpty()) {
            if (candidates.stream().anyMatch(grant -> !grant.revoked())) return deny(Reason.EXPIRED);
            return deny(candidates.isEmpty() ? Reason.NO_GRANT : Reason.REVOKED);
        }
        Instant expires = valid.stream().map(grant -> grant.activeTime().to()).min(Instant::compareTo).orElseThrow();
        // Recompute at the next start as well, so a cached decision cannot mask a new grant.
        for (Grant grant : candidates) {
            Instant start = grant.activeTime().from();
            if (!grant.revoked() && start.isAfter(now) && start.isBefore(expires)) expires = start;
        }
        return new Decision(actor, request, valid.stream().map(Grant::dataTime).toList(),
                version, expires, null);
    }

    static Decision deny(Reason reason) {
        return new Decision(null, null, List.of(), 0, Instant.MIN, reason);
    }
}
