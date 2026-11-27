package cn.iocoder.sanitation.validation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static cn.iocoder.sanitation.validation.Authorization.*;

final class PrivateFiles {
    record File(long id, long businessId, Project project, Instant eventTime, Instant expires, boolean exportOnly) {}
    record Business(long id, Project project, Instant eventTime) {}
    enum Stage { CREATE, EXECUTE, DOWNLOAD }
    record Audit(Stage stage, long id, Reason reason) {}

    private final Authorization policy;
    private final Clock clock;
    final Map<Long, File> files = new HashMap<>();
    final Map<Long, Business> business = new HashMap<>();
    final List<Audit> audit = new ArrayList<>();
    final Map<Long, Export> exports = new HashMap<>();

    private final Map<Long, List<Integer>> staging = new HashMap<>();

    private static final class Export {
        private final Actor actor;
        private final Selection scope;
        private final long file;
        private boolean ready;

        private Export(Actor actor, Selection scope, long file) {
            this.actor = actor;
            this.scope = scope;
            this.file = file;
        }
    }

    PrivateFiles(Authorization policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    boolean download(Actor actor, Selection selector, long id, int chunks, IntConsumer write) {
        var file = files.get(id);
        if (file != null && file.exportOnly()) return record(Stage.DOWNLOAD, id, Reason.OBJECT_UNAVAILABLE);
        return stream(actor, selector, id, chunks, write);
    }

    private boolean stream(Actor actor, Selection selector, long id, int chunks, IntConsumer write) {
        if (chunks <= 0 || !canDownload(actor, selector, id)) return record(Stage.DOWNLOAD, id, Reason.OBJECT_UNAVAILABLE);
        for (int chunk = 0; chunk < chunks; chunk++) {
            if (!canDownload(actor, selector, id)) return record(Stage.DOWNLOAD, id, Reason.OBJECT_UNAVAILABLE);
            write.accept(chunk);
        }
        return record(Stage.DOWNLOAD, id, null);
    }

    private boolean canDownload(Actor actor, Selection selection, long id) {
        var file = files.get(id);
        if (file == null || !clock.instant().isBefore(file.expires())) return false;
        var owner = business.get(file.businessId());
        return owner != null && owner.project().equals(file.project()) && owner.eventTime().equals(file.eventTime())
                && policy.decide(actor, selection).covers(owner.project(), Data.FILE, Action.DOWNLOAD,
                owner.eventTime(), clock.instant());
    }

    boolean create(long id, Actor actor, Selection selection, long outputFile) {
        var file = files.get(outputFile);
        if (!canExport(actor, selection, file) || !file.exportOnly() || exports.containsKey(id)) return record(Stage.CREATE, id, Reason.NO_GRANT);
        exports.put(id, new Export(actor, selection, outputFile));
        return record(Stage.CREATE, id, null);
    }

    boolean execute(long id, int chunks, IntConsumer work) {
        var export = exports.get(id);
        if (export == null || export.ready || chunks <= 0 || staging.containsKey(id)) {
            return record(Stage.EXECUTE, id, Reason.NO_GRANT);
        }
        var temporary = new ArrayList<Integer>();
        staging.put(id, temporary);
        try {
            for (int chunk = 0; chunk < chunks; chunk++) {
                if (!canExport(export.actor, export.scope, files.get(export.file))) return record(Stage.EXECUTE, id, Reason.NO_GRANT);
                work.accept(chunk);
                temporary.add(chunk);
            }
            if (!canExport(export.actor, export.scope, files.get(export.file))) return record(Stage.EXECUTE, id, Reason.NO_GRANT);
            export.ready = true;
            return record(Stage.EXECUTE, id, null);
        } catch (RuntimeException error) {
            record(Stage.EXECUTE, id, Reason.UNAVAILABLE);
            throw error;
        } finally {
            staging.remove(id);
        }
    }

    boolean downloadExport(long id, Actor actor, Selection downloadScope) {
        var export = exports.get(id);
        if (export == null || !export.ready || !export.actor.equals(actor)
                || !canExport(actor, export.scope, files.get(export.file))) {
            return record(Stage.DOWNLOAD, id, Reason.OBJECT_UNAVAILABLE);
        }
        return stream(actor, downloadScope, export.file, 1, chunk -> {});
    }

    int temporaryCount() { return staging.size(); }

    private boolean canExport(Actor actor, Selection selection, File file) {
        if (file == null || !clock.instant().isBefore(file.expires())) return false;
        var owner = business.get(file.businessId());
        return owner != null && owner.project().equals(file.project()) && owner.eventTime().equals(file.eventTime())
                && policy.decide(actor, selection).covers(owner.project(), Data.OBSERVATION, Action.EXPORT,
                owner.eventTime(), clock.instant());
    }

    private boolean record(Stage stage, long id, Reason reason) {
        audit.add(new Audit(stage, id, reason));
        return reason == null;
    }
}
