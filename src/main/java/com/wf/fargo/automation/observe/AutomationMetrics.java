package com.wf.fargo.automation.observe;

import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class AutomationMetrics {

    static final String TRANSITIONS = "automation.run.transitions";
    static final String RUNS_IN_STATE = "automation.runs.in.state";
    static final String DUPLICATES = "automation.trigger.duplicates";
    static final String APPROVALS = "automation.approvals.decided";
    static final String APPROVAL_TIME = "automation.approval.time.to.decide";
    static final String DISPATCH_LATENCY = "automation.dispatch.latency";
    static final String OUTBOX_PENDING = "automation.outbox.pending";
    static final String OUTBOX_OLDEST = "automation.outbox.oldest.pending";
    static final String OUTBOX_FAILED = "automation.outbox.failed";
    static final String STUCK = "automation.stuck.runs";
    static final String POLICY = "automation.policy.rejections";
    static final String CONFIRMATION = "automation.confirmation.rejections";

    private final MeterRegistry registry;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public AutomationMetrics(MeterRegistry registry, MongoTemplate mongoTemplate, Clock clock) {
        this.registry = registry;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        for (RunState state : RunState.values()) {
            Gauge.builder(RUNS_IN_STATE, mongoTemplate, template -> countRuns(template, state))
                    .tag("state", wire(state))
                    .register(registry);
        }
        for (OutboxDestination destination : OutboxDestination.values()) {
            Gauge.builder(OUTBOX_PENDING, mongoTemplate, template -> countPending(template, destination))
                    .tag("destination", destination.name())
                    .register(registry);
            Gauge.builder(OUTBOX_OLDEST, mongoTemplate, template -> oldestPendingSeconds(template, destination))
                    .tag("destination", destination.name())
                    .baseUnit("seconds")
                    .register(registry);
        }
    }

    public void transition(RunState from, RunState to, String templateId) {
        registry.counter(TRANSITIONS, "from", wire(from), "to", wire(to), "template", template(templateId))
                .increment();
    }

    public void duplicateTrigger(String templateId) {
        registry.counter(DUPLICATES, "template", template(templateId)).increment();
    }

    public void approvalDecided(String decision, String templateId, Duration elapsed) {
        registry.counter(APPROVALS, "decision", decision).increment();
        if (elapsed != null && !elapsed.isNegative()) {
            Timer.builder(APPROVAL_TIME)
                    .tag("template", template(templateId))
                    .register(registry)
                    .record(elapsed);
        }
    }

    public void dispatchLatency(String phase, String mode, Duration elapsed) {
        if (phase == null || mode == null || elapsed == null || elapsed.isNegative()) {
            return;
        }
        Timer.builder(DISPATCH_LATENCY)
                .tag("phase", phase)
                .tag("mode", mode)
                .register(registry)
                .record(elapsed);
    }

    public void outboxFailed(OutboxDestination destination) {
        if (destination != null) {
            registry.counter(OUTBOX_FAILED, "destination", destination.name()).increment();
        }
    }

    public void stuck(RunState state, String action) {
        registry.counter(STUCK, "state", wire(state), "action", action).increment();
    }

    public void policyRejected(String code) {
        if (code != null && !code.isBlank()) {
            registry.counter(POLICY, "code", code).increment();
        }
    }

    public void confirmationRejected(String reason) {
        registry.counter(CONFIRMATION, "reason", reason).increment();
    }

    public String templateOf(AgentRun run) {
        if (run == null || run.agentId() == null) {
            return "unknown";
        }
        PersonalAgent agent = mongoTemplate.findById(run.agentId(), PersonalAgent.class);
        return agent == null ? "unknown" : template(agent.templateId());
    }

    private long countRuns(MongoTemplate template, RunState state) {
        return template.count(Query.query(Criteria.where("state").is(state)), AgentRun.class);
    }

    private long countPending(MongoTemplate template, OutboxDestination destination) {
        return template.count(pending(destination), OutboxMessage.class);
    }

    private double oldestPendingSeconds(MongoTemplate template, OutboxDestination destination) {
        OutboxMessage oldest = template.findOne(
                pending(destination).with(org.springframework.data.domain.Sort.by("createdAt")), OutboxMessage.class);
        if (oldest == null || oldest.createdAt() == null) {
            return 0;
        }
        Instant now = clock.instant();
        if (oldest.createdAt().isAfter(now)) {
            return 0;
        }
        return Duration.between(oldest.createdAt(), now).toMillis() / 1000.0;
    }

    private static Query pending(OutboxDestination destination) {
        return Query.query(Criteria.where("status").is("pending").and("destination").is(destination));
    }

    private static String template(String templateId) {
        return templateId == null || templateId.isBlank() ? "unknown" : templateId;
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
