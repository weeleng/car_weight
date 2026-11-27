package cn.iocoder.sanitation.validation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static cn.iocoder.sanitation.validation.Authorization.*;
import static cn.iocoder.sanitation.validation.Execution.*;
import static cn.iocoder.sanitation.validation.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionTest {
    final Fixtures f = new Fixtures();
    final Selection change = selection(ONE, Data.OBSERVATION, Action.EDIT);
    final Execution execution = new Execution(f.policy, Map.of(
            "command", new Work(USER, change, Channel.USER_COMMAND),
            "fact", new Work(WORKER, selection(ONE, Data.OBSERVATION, Action.PROCESS_FACT), Channel.SYSTEM_FACT),
            "job", new Work(WORKER, view(ONE), Channel.JOB)), Map.of("session", USER));

    @Test @Tag("positive")
    void springFilterEstablishesAndClearsContext() throws Exception {
        var response = new MockHttpServletResponse();
        var request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "session");
        filter(view(ONE)).doFilter(request, response, (req, res) -> assertContext(USER, ONE));
        assertEquals(200, response.getStatus());
        assertCleared();
    }

    @Test @Tag("negative")
    void headerIsNotPrincipalAndHttpExceptionStillClears() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("tenant-id", "10");
        request.addHeader("roleId", "admin");
        var response = new MockHttpServletResponse();
        filter(view(ONE)).doFilter(request, response, (req, res) -> fail("anonymous body"));
        assertEquals(401, response.getStatus());
        assertCleared();
        var unauthorized = new MockHttpServletResponse();
        request.setUserPrincipal(() -> "session");
        filter(view(OTHER)).doFilter(request, unauthorized, (req, res) -> fail("cross-tenant selector"));
        assertEquals(404, unauthorized.getStatus());
        assertThrows(IllegalStateException.class, () -> filter(view(ONE)).doFilter(request,
                new MockHttpServletResponse(), (req, res) -> { throw new IllegalStateException("fixture"); }));
        assertCleared();
    }

    @Test @Tag("negative")
    void messageAndJobSelectorsCannotEstablishScope() {
        assertEquals(Reason.UNTRUSTED, execution.dispatch(Channel.USER_COMMAND, WORKER,
                new Claim("command", OTHER), () -> fail("simulated message header")).reason());
        assertEquals(Reason.UNTRUSTED, execution.dispatch(Channel.JOB, WORKER,
                new Claim("job", TWO), () -> fail("simulated Quartz parameter")).reason());
        assertEquals(Reason.UNTRUSTED, execution.dispatch(Channel.SYSTEM_FACT, DEVICE,
                new Claim("fact", ONE), () -> fail("device executing a fact")).reason());
        assertCleared();
    }

    @Test @Tag("negative")
    void userRevocationRejectsCommandAndPreservesSystemFact() {
        assertTrue(execution.dispatch(Channel.USER_COMMAND, WORKER,
                new Claim("command", ONE), () -> assertContext(WORKER, ONE)).allowed());
        f.revoke(USER, true);
        assertEquals(Reason.REVOKED, execution.dispatch(Channel.USER_COMMAND, WORKER,
                new Claim("command", ONE), () -> fail("stale user authorization")).reason());
        assertTrue(execution.dispatch(Channel.SYSTEM_FACT, WORKER,
                new Claim("fact", ONE), () -> assertContext(WORKER, ONE)).allowed());
        assertTrue(execution.dispatch(Channel.JOB, WORKER,
                new Claim("job", ONE), () -> assertContext(WORKER, ONE)).allowed());
        assertCleared();
    }

    @Test @Tag("positive")
    void nestedWorkRestoresOuterContextAndNormalWorkerReuse() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            long thread = worker.submit(() -> {
                execution.http("session", view(ONE), () -> {
                    var outer = execution.current();
                    execution.http("session", view(TWO), () -> assertContext(USER, TWO));
                    assertSame(outer, execution.current());
                    assertContext(USER, ONE);
                });
                assertCleared();
                return Thread.currentThread().getId();
            }).get(5, TimeUnit.SECONDS);
            worker.submit(() -> {
                assertEquals(thread, Thread.currentThread().getId());
                assertCleared();
            }).get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test @Tag("negative")
    void cancellationAndTimeoutClearWorkerWhenExecutionActuallyEnds() throws Exception {
        var arrived = new CountDownLatch(1);
        var interruptSeen = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> execution.http("session", view(ONE), () -> {
                assertContext(USER, ONE);
                arrived.countDown();
                try {
                    release.await();
                } catch (InterruptedException expected) {
                    assertContext(USER, ONE);
                    interruptSeen.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException unexpected) {
                        throw new AssertionError(unexpected);
                    }
                }
                assertContext(USER, ONE);
                throw new IllegalStateException("simulated late failure");
            }));
            try {
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.get(25, TimeUnit.MILLISECONDS));
                var next = worker.submit(this::assertCleared);
                assertFalse(next.isDone(), "the worker is still running");
                assertTrue(pending.cancel(true));
                assertTrue(interruptSeen.await(5, TimeUnit.SECONDS));
                assertFalse(next.isDone(), "cancelling the future did not stop the worker");
                release.countDown();
                next.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    @Test @Tag("negative")
    void sameWorkerAfterUncancelledFailureIsClean() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            var failed = worker.submit(() -> execution.http("session", view(ONE), () -> {
                assertContext(USER, ONE);
                throw new IllegalStateException("worker");
            }));
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> failed.get(5, TimeUnit.SECONDS)).getCause());
            worker.submit(this::assertCleared).get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test @Tag("negative")
    void queuedCancellationNeverInstallsAContext() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        try {
            var blocker = worker.submit(() -> {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            var pending = worker.submit(() -> execution.http("session", view(ONE),
                    () -> fail("cancelled task entered authorization")));
            assertTrue(pending.cancel(false));
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.CancellationException.class, pending::get);
            worker.submit(this::assertCleared).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    private OncePerRequestFilter filter(Selection selection) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                var principal = request.getUserPrincipal();
                Decision decision = execution.http(principal == null ? null : principal.getName(), selection, () -> {
                    try {
                        chain.doFilter(request, response);
                    } catch (IOException | ServletException error) {
                        throw new IllegalStateException(error);
                    }
                });
                if (!decision.allowed()) response.setStatus(decision.reason() == Reason.MISSING_IDENTITY ? 401 : 404);
            }
        };
    }

    private void assertContext(Actor actor, Project project) {
        assertEquals(actor, execution.current().actor());
        assertEquals(project, execution.current().project());
        assertEquals(project.tenant(), execution.tenant());
        assertEquals(false, execution.ignored());
        assertEquals(execution.current().auditId(), execution.audit());
        assertEquals(actor, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    private void assertCleared() {
        assertNull(execution.current());
        assertNull(execution.tenant());
        assertNull(execution.ignored());
        assertNull(execution.audit());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        SecurityContextHolder.clearContext();
    }
}
