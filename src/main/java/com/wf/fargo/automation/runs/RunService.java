package com.wf.fargo.automation.runs;

import com.mongodb.MongoException;
import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.dispatch.TaskDispatcher;
import com.wf.fargo.automation.dispatch.TaskFactory;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.notify.ActivityWriter;
import com.wf.fargo.automation.notify.MessageTemplates;
import com.wf.fargo.automation.observe.AutomationMetrics;
import com.wf.fargo.automation.observe.RunTrace;
import com.wf.fargo.automation.outbox.OutboxWriter;
import com.wf.fargo.automation.store.RunStateStore;
import com.wf.fargo.automation.store.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final MongoTemplate mongoTemplate;
    private final TransactionRunner transactions;
    private final RunStateStore runs;
    private final OutboxWriter outbox;
    private final TaskFactory tasks;
    private final TaskDispatcher dispatcher;
    private final ActivityWriter activities;
    private final Clock clock;
    private final RunTrace traces;
    private final AutomationMetrics metrics;

    public RunService(
            MongoTemplate mongoTemplate,
            TransactionRunner transactions,
            RunStateStore runs,
            OutboxWriter outbox,
            TaskFactory tasks,
            TaskDispatcher dispatcher,
            ActivityWriter activities,
            Clock clock,
            RunTrace traces,
            AutomationMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.transactions = transactions;
        this.runs = runs;
        this.outbox = outbox;
        this.tasks = tasks;
        this.dispatcher = dispatcher;
        this.activities = activities;
        this.clock = clock;
        this.traces = traces;
        this.metrics = metrics;
    }

    public void start(TriggerFired trigger) {
        if (trigger == null || trigger.agentId() == null || trigger.periodId() == null) {
            throw new PoisonMessageException("Trigger is missing agentId or periodId");
        }
        PersonalAgent agent = mongoTemplate.findById(trigger.agentId(), PersonalAgent.class);
        if (agent == null) {
            log.warn("trigger_agent_missing agentId={}", trigger.agentId());
            return;
        }
        try {
            transactions.inTransaction(() -> {
                startNew(agent, trigger);
                return null;
            });
        } catch (RuntimeException ex) {
            if (duplicate(ex)) {
                metrics.duplicateTrigger(agent.templateId());
                log.info("duplicate_trigger agentId={} periodId={}", trigger.agentId(), trigger.periodId());
                return;
            }
            throw ex;
        }
    }

    private void startNew(PersonalAgent agent, TriggerFired trigger) {
        Instant now = clock.instant();
        String traceId = traces.open();
        String runId = "run_" + Ulids.next(clock.millis());
        Map<String, Object> context = trigger.context() == null ? Map.of() : trigger.context();
        AgentRun inserted = new AgentRun(
                runId,
                agent.agentId(),
                agent.customerId(),
                trigger.periodId(),
                agent.agentId() + ":" + trigger.periodId(),
                trigger.eventRef(),
                context,
                RunState.TRIGGERED,
                List.of(),
                null,
                null,
                null,
                null,
                traceId,
                new RunAttempts(0, 0),
                now,
                now,
                1);
        mongoTemplate.insert(inserted);
        try (var ignored = traces.scope(traceId, agent.customerId(), agent.agentId(), runId)) {
            String skipReason = skipReason(agent, runId, now);
            if (skipReason != null) {
                transition(runId, RunState.SKIPPED, skipReason, null);
                activity(agent, runId, ActivityType.SKIPPED, now);
                return;
            }
            String taskId = "tsk_" + Ulids.next(clock.millis());
            AgentTemplate template = template(agent);
            transition(runId, RunState.PREVIEWING, null, update -> {
                update.set("currentTaskId", taskId);
                update.set("attempts", new RunAttempts(1, 0));
            });
            AgentRun previewing = mongoTemplate.findById(runId, AgentRun.class);
            outbox.write(
                    dispatcher.destination(),
                    agent.customerId(),
                    tasks.preview(previewing, agent, template, taskId));
            activity(agent, runId, ActivityType.FIRED, now);
        }
    }

    private String skipReason(PersonalAgent agent, String runId, Instant now) {
        if (agent.status() != AgentStatus.ON) {
            return "agent_paused";
        }
        Grant grant = mongoTemplate.findOne(Query.query(Criteria.where("agentId").is(agent.agentId())), Grant.class);
        if (grant == null || grant.revokedAt() != null) {
            return "grant_revoked";
        }
        AgentTemplate template = template(agent);
        Integer maxRunsPerDay = template.limits() == null ? null : template.limits().maxRunsPerDay();
        if (maxRunsPerDay != null && runsToday(agent.agentId(), runId, now) >= maxRunsPerDay) {
            return "daily_limit";
        }
        return null;
    }

    private long runsToday(String agentId, String runId, Instant now) {
        LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("agentId").is(agentId),
                Criteria.where("_id").ne(runId),
                Criteria.where("state").ne(RunState.SKIPPED),
                Criteria.where("createdAt").gte(start),
                Criteria.where("createdAt").lt(end)));
        return mongoTemplate.count(query, AgentRun.class);
    }

    private AgentTemplate template(PersonalAgent agent) {
        AgentTemplate template = mongoTemplate.findOne(
                Query.query(Criteria.where("templateId")
                        .is(agent.templateId())
                        .and("version")
                        .is(agent.templateVersion())),
                AgentTemplate.class);
        if (template == null || template.action() == null) {
            throw new IllegalStateException("Template is missing for agent " + agent.agentId());
        }
        return template;
    }

    private void transition(
            String runId,
            RunState next,
            String reason,
            Consumer<Update> extra) {
        if (runs.transition(runId, RunState.TRIGGERED, next, reason, extra).isEmpty()) {
            throw new IllegalStateException("Run " + runId + " left TRIGGERED before it could start");
        }
        log.info("run_transition from={} to={} reason={} runId={}", RunState.TRIGGERED, next, reason, runId);
    }

    private void activity(PersonalAgent agent, String runId, ActivityType type, Instant now) {
        activities.write(
                agent.customerId(),
                agent.agentId(),
                runId,
                type,
                MessageTemplates.render(type, agent.name()),
                Map.of());
    }

    private static boolean duplicate(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            if (current instanceof MongoException mongo && mongo.getCode() == 11000) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }
}
