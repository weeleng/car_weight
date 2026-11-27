package cn.iocoder.sanitation.validation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static cn.iocoder.sanitation.validation.Authorization.*;

final class Fixtures {
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    static final Window FULL = new Window(START, START.plusSeconds(100));
    static final Window DATA_TIME = new Window(START.minusSeconds(100), START.plusSeconds(100));
    static final Project ONE = new Project(10, 100);
    static final Project TWO = new Project(10, 101);
    static final Project OTHER = new Project(20, 100);
    static final Project THREE = new Project(10, 102);
    static final Actor USER = new Actor(Kind.PERSON, 1, 10);
    static final Actor REGULATOR = new Actor(Kind.PERSON, 2, 30);
    static final Actor ADMIN = new Actor(Kind.PERSON, 3, 10);
    static final Actor WORKER = new Actor(Kind.SERVICE, 4, 10);
    static final Actor DEVICE = new Actor(Kind.DEVICE, 5, 10);

    final TestClock clock = new TestClock();
    final Authority authority;
    final Authorization policy;

    Fixtures() {
        var subjects = new HashMap<Actor, Subject>();
        for (Actor actor : List.of(USER, REGULATOR, ADMIN, WORKER, DEVICE)) {
            subjects.put(actor, new Subject(true, EnumSet.allOf(Action.class)));
        }
        var tenants = java.util.Map.of(10L, new Tenant(TenantType.OPERATOR, true, Set.of(Capability.OPERATE)),
                20L, new Tenant(TenantType.OPERATOR, true, Set.of(Capability.OPERATE)),
                30L, new Tenant(TenantType.REGULATOR, true, Set.of(Capability.REGULATE)));
        var projects = java.util.Map.of(ONE, true, TWO, true, OTHER, true);
        var grants = new ArrayList<Grant>();
        for (Project project : List.of(ONE, TWO)) {
            grants.add(grant(USER, project, Data.OBSERVATION, Action.VIEW, Action.EDIT, Action.EXPORT));
        }
        grants.add(grant(USER, ONE, Data.FILE, Action.DOWNLOAD));
        grants.add(grant(REGULATOR, ONE, Data.OBSERVATION, Action.VIEW));
        grants.add(grant(WORKER, ONE, Data.OBSERVATION, Action.VIEW, Action.EDIT, Action.PROCESS_FACT));
        authority = new Authority(new Snapshot(subjects, tenants, projects, grants));
        policy = new Authorization(authority, clock);
    }

    static Grant grant(Actor actor, Project project, Data data, Action... actions) {
        return new Grant(actor, project, data, Set.of(actions), FULL, DATA_TIME, actor.home() != project.tenant(), false);
    }

    static Selection view(Project project) { return selection(project, Data.OBSERVATION, Action.VIEW); }
    static Selection selection(Project project, Data data, Action action) {
        return new Selection(project, data, action, DATA_TIME);
    }

    void grants(UnaryOperator<List<Grant>> update) {
        authority.change(state -> new Snapshot(state.subjects(), state.tenants(), state.projects(),
                update.apply(new ArrayList<>(state.grants()))));
    }

    void revoke(Actor actor, boolean value) {
        grants(items -> items.stream().map(g -> !g.actor().equals(actor) ? g :
                new Grant(g.actor(), g.project(), g.data(), g.projectRole(), g.activeTime(),
                        g.dataTime(), g.regulatory(), value)).toList());
    }

    void subject(Actor actor, Subject subject) {
        authority.change(state -> {
            var subjects = new HashMap<>(state.subjects());
            subjects.put(actor, subject);
            return new Snapshot(subjects, state.tenants(), state.projects(), state.grants());
        });
    }

    void project(Project project, boolean active) {
        authority.change(state -> {
            var projects = new HashMap<>(state.projects());
            projects.put(project, active);
            return new Snapshot(state.subjects(), state.tenants(), projects, state.grants());
        });
    }

    void tenant(long id, boolean active) {
        authority.change(state -> {
            var tenants = new HashMap<>(state.tenants());
            tenants.put(id, new Tenant(tenants.get(id).type(), active, tenants.get(id).capabilities()));
            return new Snapshot(state.subjects(), tenants, state.projects(), state.grants());
        });
    }

    static List<Observations.Observation> observations() {
        return List.of(new Observations.Observation(1, ONE, START.minusSeconds(50), "a", 2, 5),
                new Observations.Observation(2, ONE, START, "b", 3, 6),
                new Observations.Observation(3, TWO, START, "a", 80, 800),
                new Observations.Observation(4, OTHER, START, "a", 90, 900),
                new Observations.Observation(5, ONE, START.plusSeconds(120), "a", 90, 900));
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(START);
        void set(Instant value) { time.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return time.get(); }
    }
}
