package cn.iocoder.sanitation.validation;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static cn.iocoder.sanitation.validation.Authorization.*;

final class Execution {
    enum Channel { HTTP, USER_COMMAND, SYSTEM_FACT, JOB }

    record Work(Actor actor, Selection selection, Channel channel) {}
    record Claim(String workId, Project requestedProject) {}
    record Context(Actor actor, Project project, Decision scope, String auditId) {}

    private final ThreadLocal<Context> context = new ThreadLocal<>();
    private final ThreadLocal<Long> tenant = new ThreadLocal<>();
    private final ThreadLocal<Boolean> ignore = new ThreadLocal<>();
    private final ThreadLocal<String> audit = new ThreadLocal<>();
    private final Authorization policy;
    private final Map<String, Work> work;
    private final Map<String, Actor> authenticatedHttp;

    Execution(Authorization policy, Map<String, Work> work, Map<String, Actor> authenticatedHttp) {
        this.policy = policy;
        this.work = Map.copyOf(work);
        this.authenticatedHttp = Map.copyOf(authenticatedHttp);
    }

    Decision http(String verifiedSession, Selection selection, Runnable body) {
        if (current() == null) clear();
        Actor actor = verifiedSession == null ? null : authenticatedHttp.get(verifiedSession);
        return run(actor, selection, "http", body);
    }

    Decision dispatch(Channel channel, Actor worker, Claim claim, Runnable body) {
        if (current() == null) clear();
        Work saved = claim == null || claim.workId() == null ? null : work.get(claim.workId());
        // The envelope contributes selectors. Only the trusted work registry determines ownership.
        if (worker == null || worker.kind() != Kind.SERVICE || saved == null
                || saved.channel() != channel || (channel == Channel.SYSTEM_FACT
                && saved.selection().action() != Action.PROCESS_FACT)
                || !saved.selection().project().equals(claim.requestedProject())) {
            return deny(Reason.UNTRUSTED);
        }
        Decision executor = policy.decide(worker, saved.selection());
        if (!executor.allowed()) return executor;
        if (channel == Channel.USER_COMMAND) {
            if (saved.actor().kind() != Kind.PERSON) return deny(Reason.UNTRUSTED);
            Decision caller = policy.decide(saved.actor(), saved.selection());
            if (!caller.allowed()) return caller;
        } else if (channel == Channel.HTTP || !worker.equals(saved.actor())) {
            return deny(Reason.UNTRUSTED);
        }
        return run(worker, saved.selection(), claim.workId(), body);
    }

    private Decision run(Actor actor, Selection selection, String auditId, Runnable body) {
        Decision decision = policy.decide(actor, selection);
        if (!decision.allowed()) return decision;
        Context next = new Context(actor, selection.project(), decision, auditId);
        scoped(next, () -> {
            body.run();
            return null;
        });
        return decision;
    }

    private <T> T scoped(Context next, Supplier<T> body) {
        Context previous = context.get();
        var previousSecurity = SecurityContextHolder.getContext();
        if (previous == null) clear();
        var active = SecurityContextHolder.createEmptyContext();
        active.setAuthentication(new UsernamePasswordAuthenticationToken(next.actor(), null, List.of()));
        SecurityContextHolder.setContext(active);
        establish(next);
        try {
            return body.get();
        } finally {
            clear();
            if (previous != null) {
                establish(previous);
                SecurityContextHolder.setContext(previousSecurity);
            }
        }
    }

    private void establish(Context current) {
        context.set(current);
        tenant.set(current.project().tenant());
        ignore.set(false);
        audit.set(current.auditId());
    }

    private void clear() {
        context.remove();
        tenant.remove();
        ignore.remove();
        audit.remove();
        SecurityContextHolder.clearContext();
    }

    Context current() { return context.get(); }
    Long tenant() { return tenant.get(); }
    Boolean ignored() { return ignore.get(); }
    String audit() { return audit.get(); }
}
