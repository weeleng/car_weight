package cn.iocoder.sanitation.validation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static cn.iocoder.sanitation.validation.Authorization.*;
import static cn.iocoder.sanitation.validation.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PolicyTest {
    final Fixtures f = new Fixtures();

    @Test @Tag("positive")
    void independentMembershipAllowsTwoProjects() {
        assertTrue(f.policy.decide(USER, view(ONE)).allowed());
        assertTrue(f.policy.decide(USER, view(TWO)).allowed());
        assertTrue(f.policy.decide(REGULATOR, view(ONE)).allowed());
    }

    record Case(String label, Actor actor, Selection selection, Reason reason) {
        @Override public String toString() { return label; }
    }

    static Stream<Case> denyCases() {
        return Stream.of(
                new Case("missing identity", null, view(ONE), Reason.MISSING_IDENTITY),
                new Case("missing scope", USER, null, Reason.MISSING_SCOPE),
                new Case("missing tenant", USER, view(new Project(0, 100)), Reason.MISSING_SCOPE),
                new Case("missing project", USER, view(new Project(10, 0)), Reason.MISSING_SCOPE),
                new Case("untrusted identity", new Actor(Kind.PERSON, 1, 30), view(ONE), Reason.UNTRUSTED),
                new Case("wrong project", USER, view(new Project(10, 909)), Reason.PROJECT_INACTIVE),
                new Case("wrong tenant", USER, view(new Project(666, 100)), Reason.TENANT_INACTIVE),
                new Case("other tenant same id", USER, view(OTHER), Reason.NO_GRANT),
                new Case("same tenant no membership", ADMIN, view(ONE), Reason.NO_GRANT),
                new Case("regulator other project", REGULATOR, view(TWO), Reason.NO_GRANT),
                new Case("regulator cannot modify", REGULATOR, selection(ONE, Data.OBSERVATION, Action.EDIT), Reason.NO_GRANT),
                new Case("regulator other data type", REGULATOR, selection(ONE, Data.FILE, Action.VIEW), Reason.NO_GRANT),
                new Case("device is not a user", DEVICE, view(ONE), Reason.DEVICE_IDENTITY));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("denyCases") @Tag("negative")
    void deniesInvalidInputs(Case scenario) {
        assertEquals(scenario.reason(), f.policy.decide(scenario.actor(), scenario.selection()).reason());
    }

    @Test @Tag("negative")
    void tenantTypeDoesNotConferCapability() {
        assertTrue(f.policy.decide(REGULATOR, view(ONE)).allowed());
        f.authority.change(state -> {
            var tenants = new java.util.HashMap<>(state.tenants());
            tenants.put(30L, new Tenant(TenantType.REGULATOR, true, Set.of()));
            return new Snapshot(state.subjects(), tenants, state.projects(), state.grants());
        });
        assertEquals(Reason.NO_GRANT, f.policy.decide(REGULATOR, view(ONE)).reason());
    }

    @Test @Tag("negative")
    void newProjectAndEmptyGrantsFailClosed() {
        f.project(THREE, true);
        assertEquals(Reason.NO_GRANT, f.policy.decide(REGULATOR, view(THREE)).reason());
        f.grants(items -> List.of());
        assertEquals(Reason.NO_GRANT, f.policy.decide(USER, view(ONE)).reason());
    }

    @Test @Tag("negative")
    void functionIntersectionAndRoleShrinkInvalidateAllows() {
        assertTrue(f.policy.decide(USER, view(ONE)).allowed());
        f.grants(items -> {
            items.removeIf(g -> g.actor().equals(USER) && g.project().equals(ONE) && g.data() == Data.OBSERVATION);
            items.add(grant(USER, ONE, Data.OBSERVATION, Action.EDIT));
            return items;
        });
        assertEquals(Reason.NO_GRANT, f.policy.decide(USER, view(ONE)).reason());
        f.subject(USER, new Subject(true, Set.of(Action.VIEW)));
        assertEquals(Reason.FUNCTION_DENIED,
                f.policy.decide(USER, selection(ONE, Data.OBSERVATION, Action.EDIT)).reason());
    }

    @Test @Tag("negative")
    void tenantProjectUserAndAuthorityAreRechecked() {
        assertTrue(f.policy.decide(REGULATOR, view(ONE)).allowed());
        f.authority.available(false);
        assertEquals(Reason.UNAVAILABLE, f.policy.decide(REGULATOR, view(ONE)).reason());
        assertEquals(Reason.UNAVAILABLE, f.policy.decide(USER, selection(ONE, Data.FILE, Action.DOWNLOAD)).reason());
        f.authority.available(true);
        f.tenant(10L, false);
        assertEquals(Reason.TENANT_INACTIVE, f.policy.decide(REGULATOR, view(ONE)).reason());
        f.tenant(10L, true);
        f.tenant(30L, false);
        assertEquals(Reason.TENANT_INACTIVE, f.policy.decide(REGULATOR, view(ONE)).reason());
        f.tenant(30L, true);
        f.project(ONE, false);
        assertEquals(Reason.PROJECT_INACTIVE, f.policy.decide(REGULATOR, view(ONE)).reason());
        f.project(ONE, true);
        f.subject(REGULATOR, new Subject(false, Set.of(Action.VIEW)));
        assertEquals(Reason.UNTRUSTED, f.policy.decide(REGULATOR, view(ONE)).reason());
    }

    @Test @Tag("negative")
    void expiryAndLostNotificationDoNotPreserveAllow() {
        assertSame(f.policy.decide(USER, view(ONE)), f.policy.decide(USER, view(ONE)));
        f.revoke(USER, true);
        assertEquals(Reason.REVOKED, f.policy.decide(USER, view(ONE)).reason());
        f.revoke(USER, false);
        assertTrue(f.policy.decide(USER, view(ONE)).allowed());
        f.clock.set(FULL.to());
        assertEquals(Reason.EXPIRED, f.policy.decide(USER, view(ONE)).reason());
    }

    @Test @Tag("negative")
    void concurrentOldVersionRefillIsRejected() throws Exception {
        var captured = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var stale = pool.submit(() -> {
                Decision old = f.policy.decide(REGULATOR, view(ONE));
                captured.countDown();
                if (!revoked.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                return f.policy.refill(old);
            });
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            f.revoke(REGULATOR, true);
            revoked.countDown();
            assertFalse(stale.get(5, TimeUnit.SECONDS));
            assertEquals(Reason.REVOKED, f.policy.decide(REGULATOR, view(ONE)).reason());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test @Tag("negative")
    void cacheKeySeparatesIdentityAndScopeDimensions() {
        var base = new Key(USER, ONE, Data.OBSERVATION, Action.VIEW, DATA_TIME, 1);
        var variants = List.of(new Key(ADMIN, ONE, Data.OBSERVATION, Action.VIEW, DATA_TIME, 1),
                new Key(new Actor(Kind.PERSON, 1, 30), ONE, Data.OBSERVATION, Action.VIEW, DATA_TIME, 1),
                new Key(USER, OTHER, Data.OBSERVATION, Action.VIEW, DATA_TIME, 1),
                new Key(USER, TWO, Data.OBSERVATION, Action.VIEW, DATA_TIME, 1),
                new Key(USER, ONE, Data.FILE, Action.VIEW, DATA_TIME, 1),
                new Key(USER, ONE, Data.OBSERVATION, Action.EXPORT, DATA_TIME, 1),
                new Key(USER, ONE, Data.OBSERVATION, Action.VIEW, FULL, 1),
                new Key(USER, ONE, Data.OBSERVATION, Action.VIEW, DATA_TIME, 2));
        variants.forEach(key -> assertNotEquals(base, key));
        assertNotSame(f.policy.decide(USER, view(ONE)), f.policy.decide(USER, view(TWO)));
        assertNotSame(f.policy.decide(USER, view(ONE)), f.policy.decide(USER, new Selection(
                ONE, Data.OBSERVATION, Action.VIEW, FULL)));
    }
}
