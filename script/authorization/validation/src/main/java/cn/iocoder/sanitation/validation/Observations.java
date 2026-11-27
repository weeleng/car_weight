package cn.iocoder.sanitation.validation;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static cn.iocoder.sanitation.validation.Authorization.*;

final class Observations {
    record Observation(long id, Project project, Instant eventTime, String cell, int numerator, int denominator) {}
    record Totals(long count, int numerator, int denominator) {}

    private final Authorization policy;
    private final Clock clock;
    private final List<Observation> rows;

    Observations(Authorization policy, Clock clock, List<Observation> rows) {
        this.policy = policy;
        this.clock = clock;
        this.rows = List.copyOf(rows);
    }

    private List<Observation> visible(Actor actor, Selection selection) {
        Decision scope = policy.decide(actor, selection);
        Instant now = clock.instant();
        return rows.stream().filter(row -> scope.covers(row.project(), Data.OBSERVATION,
                Action.VIEW, row.eventTime(), now)).toList();
    }

    Optional<Observation> byId(Actor actor, Selection selection, long objectId) {
        return visible(actor, selection).stream().filter(row -> row.id() == objectId).findFirst();
    }

    List<Observation> page(Actor actor, Selection selection, int offset, int limit) {
        return visible(actor, selection).stream()
                .sorted(Comparator.comparing(Observation::eventTime).thenComparingLong(Observation::id))
                .skip(offset).limit(limit).toList();
    }

    List<Observation> drillDown(Actor actor, Selection selection, String cell) {
        return visible(actor, selection).stream().filter(row -> row.cell().equals(cell)).toList();
    }

    Map<String, Totals> map(Actor actor, Selection selection) {
        var grouped = visible(actor, selection).stream().collect(Collectors.groupingBy(Observation::cell));
        var totals = new LinkedHashMap<String, Totals>();
        grouped.forEach((cell, members) -> totals.put(cell, new Totals(members.size(),
                members.stream().mapToInt(Observation::numerator).sum(),
                members.stream().mapToInt(Observation::denominator).sum())));
        return Map.copyOf(totals);
    }
}
