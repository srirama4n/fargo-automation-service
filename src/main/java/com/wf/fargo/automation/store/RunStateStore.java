package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.RunStateMachine;
import com.wf.fargo.automation.domain.StateHistoryEntry;
import com.wf.fargo.automation.observe.AutomationMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class RunStateStore {

    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final AutomationMetrics metrics;

    public RunStateStore(MongoTemplate mongoTemplate, Clock clock, AutomationMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.metrics = metrics;
    }

    public Optional<AgentRun> transition(
            String runId, RunState expected, RunState next, String reason, Consumer<Update> extra) {
        return transition(runId, expected, next, reason, extra, null);
    }

    public Optional<AgentRun> transition(
            String runId,
            RunState expected,
            RunState next,
            String reason,
            Consumer<Update> extra,
            String expectedTaskId) {
        if (!RunStateMachine.canTransition(expected, next)) {
            throw new IllegalStateException("Cannot transition " + expected + " to " + next);
        }
        Instant now = clock.instant();
        Update update = new Update()
                .set("state", next)
                .set("updatedAt", now)
                .push("stateHistory", new StateHistoryEntry(expected, next, now, reason));
        if (extra != null) {
            extra.accept(update);
        }
        Criteria criteria = Criteria.where("_id").is(runId).and("state").is(expected);
        if (expectedTaskId != null) {
            criteria = criteria.and("currentTaskId").is(expectedTaskId);
        }
        AgentRun updated = mongoTemplate.findAndModify(
                Query.query(criteria),
                update,
                FindAndModifyOptions.options().returnNew(true),
                AgentRun.class);
        if (updated != null) {
            metrics.transition(expected, next, metrics.templateOf(updated));
        }
        return Optional.ofNullable(updated);
    }
}
