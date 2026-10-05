package com.wf.fargo.automation.replies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.common.CanonicalJson;
import com.wf.fargo.automation.common.Hashing;
import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.dispatch.TaskDispatcher;
import com.wf.fargo.automation.dispatch.TaskFactory;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunError;
import com.wf.fargo.automation.domain.RunPreview;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.notify.ActivityWriter;
import com.wf.fargo.automation.notify.MessageTemplates;
import com.wf.fargo.automation.notify.Notifier;
import com.wf.fargo.automation.observe.AutomationMetrics;
import com.wf.fargo.automation.observe.RunTrace;
import com.wf.fargo.automation.outbox.OutboxWriter;
import com.wf.fargo.automation.runs.PoisonMessageException;
import com.wf.fargo.automation.store.RunStateStore;
import com.wf.fargo.automation.store.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class ReplyHandler {

    private static final Logger log = LoggerFactory.getLogger(ReplyHandler.class);
    private static final int MAX_ATTEMPTS = 3;

    private final MongoTemplate mongoTemplate;
    private final TransactionRunner transactions;
    private final RunStateStore runs;
    private final OutboxWriter outbox;
    private final TaskFactory tasks;
    private final TaskDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final ActivityWriter activities;
    private final Notifier notifier;
    private final Clock clock;
    private final RunTrace traces;
    private final AutomationMetrics metrics;

    public ReplyHandler(
            MongoTemplate mongoTemplate,
            TransactionRunner transactions,
            RunStateStore runs,
            OutboxWriter outbox,
            TaskFactory tasks,
            TaskDispatcher dispatcher,
            ObjectMapper objectMapper,
            AppProperties properties,
            ActivityWriter activities,
            Notifier notifier,
            Clock clock,
            RunTrace traces,
            AutomationMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.transactions = transactions;
        this.runs = runs;
        this.outbox = outbox;
        this.tasks = tasks;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.activities = activities;
        this.notifier = notifier;
        this.clock = clock;
        this.traces = traces;
        this.metrics = metrics;
    }

    public void handle(Map<String, Object> reply) {
        if (reply == null) {
            throw new PoisonMessageException("Reply is missing");
        }
        String taskId = text(reply.get("taskId"));
        String runId = text(reply.get("runId"));
        String phase = text(reply.get("phase"));
        String status = text(reply.get("status"));
        if (taskId == null || runId == null || phase == null || status == null) {
            throw new PoisonMessageException("Reply is missing taskId, runId, phase, or status");
        }
        AgentRun run = mongoTemplate.findById(runId, AgentRun.class);
        String traceId = run == null ? text(reply.get("traceId")) : first(run.traceId(), text(reply.get("traceId")));
        try (var ignored = traces.scope(
                traceId, run == null ? text(reply.get("customerId")) : run.customerId(),
                run == null ? null : run.agentId(),
                runId)) {
            if (run == null || !taskId.equals(run.currentTaskId())) {
                log.info("stale_reply taskId={} runId={}", taskId, runId);
                return;
            }
            recordDispatch(taskId, phase);
            if ("error".equals(status)) {
                onError(run, phase, taskId, errorOf(reply.get("error")));
                return;
            }
            if (!"ok".equals(status)) {
                throw new PoisonMessageException("Unknown reply status");
            }
            if ("preview".equals(phase)) {
                onPreview(run, taskId, previewOf(reply.get("quote")));
                return;
            }
            if ("execute".equals(phase)) {
                onExecute(run, taskId, copyMap(reply.get("outcome")));
                return;
            }
            throw new PoisonMessageException("Unknown reply phase");
        }
    }

    private void recordDispatch(String taskId, String phase) {
        OutboxMessage message = mongoTemplate.findOne(
                Query.query(Criteria.where("payload.taskId").is(taskId).and("sentAt").ne(null)), OutboxMessage.class);
        if (message == null || message.sentAt() == null) {
            return;
        }
        metrics.dispatchLatency(phase, properties.dispatch().mode(), Duration.between(message.sentAt(), clock.instant()));
    }

    private static String first(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private void onPreview(AgentRun run, String taskId, RunPreview preview) {
        PersonalAgent agent = agent(run);
        AgentTemplate template = template(agent);
        transactions.inTransaction(() -> {
            if (agent.mode() == AgentMode.ASK) {
                ask(run, agent, template, taskId, preview);
            } else if (agent.mode() == AgentMode.ACT_THEN_TELL) {
                act(run, agent, template, taskId, preview);
            } else if (agent.mode() == AgentMode.READ) {
                read(run, agent, taskId, preview);
            }
            return null;
        });
    }

    private void ask(AgentRun run, PersonalAgent agent, AgentTemplate template, String taskId, RunPreview preview) {
        if (runs.transition(run.runId(), RunState.PREVIEWING, RunState.AWAITING_APPROVAL, null,
                        update -> update.set("preview", preview), taskId)
                .isEmpty()) {
            log.info("stale_reply taskId={} runId={}", taskId, run.runId());
            return;
        }
        Instant now = clock.instant();
        String approvalId = "apr_" + Ulids.next(clock.millis());
        Duration ttl = template.approvalTtl() != null
                ? template.approvalTtl()
                : properties.limits().defaultApprovalTtl();
        String message = MessageTemplates.render(
                ActivityType.APPROVAL_REQUESTED, agent.name(), placeholders(run, preview, null));
        mongoTemplate.insert(new Approval(
                approvalId,
                run.runId(),
                run.agentId(),
                run.customerId(),
                Hashing.sha256Prefixed(CanonicalJson.of(objectMapper.valueToTree(preview))),
                ApprovalStatus.PENDING,
                now.plus(ttl),
                null,
                null,
                null,
                now,
                now,
                1));
        activity(run, agent, ActivityType.APPROVAL_REQUESTED, message, now);
        notifier.push(
                run.customerId(),
                agent.name(),
                message,
                "wf://fargo/approvals/" + approvalId,
                "agent_approval",
                run.runId());
        log.info("run_transition from={} to={} reason={} runId={}", RunState.PREVIEWING, RunState.AWAITING_APPROVAL, null, run.runId());
    }

    private void act(AgentRun run, PersonalAgent agent, AgentTemplate template, String taskId, RunPreview preview) {
        String executeTaskId = "tsk_" + Ulids.next(clock.millis());
        int previewAttempts = attempts(run).preview();
        if (runs.transition(run.runId(), RunState.PREVIEWING, RunState.EXECUTING, null, update -> {
                    update.set("preview", preview);
                    update.set("currentTaskId", executeTaskId);
                    update.set("attempts", new RunAttempts(previewAttempts, 1));
                }, taskId)
                .isEmpty()) {
            log.info("stale_reply taskId={} runId={}", taskId, run.runId());
            return;
        }
        outbox.write(
                dispatcher.destination(),
                run.customerId(),
                tasks.execute(run, agent, template, executeTaskId, preview.quoteId()));
        log.info("run_transition from={} to={} reason={} runId={}", RunState.PREVIEWING, RunState.EXECUTING, null, run.runId());
    }

    private void read(AgentRun run, PersonalAgent agent, String taskId, RunPreview preview) {
        if (runs.transition(run.runId(), RunState.PREVIEWING, RunState.DONE, null,
                        update -> update.set("preview", preview), taskId)
                .isEmpty()) {
            log.info("stale_reply taskId={} runId={}", taskId, run.runId());
            return;
        }
        Instant now = clock.instant();
        String message = MessageTemplates.render(ActivityType.NOTIFIED, agent.name(), placeholders(run, preview, null));
        activity(run, agent, ActivityType.NOTIFIED, message, now);
        notifier.push(run.customerId(), agent.name(), message, null, "agent_update", run.runId());
        log.info("run_transition from={} to={} reason={} runId={}", RunState.PREVIEWING, RunState.DONE, null, run.runId());
    }

    private void onExecute(AgentRun run, String taskId, Map<String, Object> outcome) {
        PersonalAgent agent = agent(run);
        AgentTemplate template = template(agent);
        transactions.inTransaction(() -> {
            if (runs.transition(run.runId(), RunState.EXECUTING, RunState.DONE, null,
                            update -> update.set("outcome", outcome), taskId)
                    .isEmpty()) {
                log.info("stale_reply taskId={} runId={}", taskId, run.runId());
                return null;
            }
            Instant now = clock.instant();
            String message = MessageTemplates.render(ActivityType.COMPLETED, agent.name(), Map.of());
            activity(run, agent, ActivityType.COMPLETED, message, now);
            String deepLink = undoLink(agent, template, run.runId());
            notifier.push(run.customerId(), agent.name(), message, deepLink, "agent_update", run.runId());
            log.info("run_transition from={} to={} reason={} runId={}", RunState.EXECUTING, RunState.DONE, null, run.runId());
            return null;
        });
    }

    private void onError(AgentRun run, String phase, String taskId, Map<String, Object> error) {
        int used = "execute".equals(phase) ? attempts(run).execute() : attempts(run).preview();
        boolean retryable = Boolean.TRUE.equals(error.get("retryable"));
        if (retryable && used < MAX_ATTEMPTS) {
            retry(run, phase, taskId, used + 1);
            return;
        }
        fail(run, phase, taskId, text(error.get("code")), text(error.get("message")));
    }

    private void retry(AgentRun run, String phase, String taskId, int nextAttempt) {
        PersonalAgent agent = agent(run);
        AgentTemplate template = template(agent);
        String newTaskId = "tsk_" + Ulids.next(clock.millis());
        RunAttempts attempts = attempts(run);
        RunAttempts updatedAttempts = "execute".equals(phase)
                ? new RunAttempts(attempts.preview(), nextAttempt)
                : new RunAttempts(nextAttempt, attempts.execute());
        Map<String, Object> payload = "execute".equals(phase)
                ? tasks.execute(run, agent, template, newTaskId, run.preview() == null ? null : run.preview().quoteId())
                : tasks.preview(run, agent, template, newTaskId);
        Instant when = clock.instant().plus(backoff(nextAttempt));
        transactions.inTransaction(() -> {
            AgentRun claimed = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id").is(run.runId()).and("state").is(run.state()).and("currentTaskId").is(taskId)),
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("currentTaskId", newTaskId)
                            .set("attempts", updatedAttempts)
                            .set("updatedAt", clock.instant()),
                    org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                    AgentRun.class);
            if (claimed == null) {
                log.info("stale_reply taskId={} runId={}", taskId, run.runId());
                return null;
            }
            outbox.write(dispatcher.destination(), run.customerId(), payload, when);
            log.info("reply_retry phase={} attempt={} runId={}", phase, nextAttempt, run.runId());
            return null;
        });
    }

    private void fail(AgentRun run, String phase, String taskId, String code, String message) {
        PersonalAgent agent = agent(run);
        RunState expected = "execute".equals(phase) ? RunState.EXECUTING : RunState.PREVIEWING;
        String customerText = MessageTemplates.render(ActivityType.FAILED, agent.name(), Map.of("phase", phase));
        transactions.inTransaction(() -> {
            if (runs.transition(run.runId(), expected, RunState.FAILED, code,
                            update -> update.set("error", new RunError(code, message)), taskId)
                    .isEmpty()) {
                log.info("stale_reply taskId={} runId={}", taskId, run.runId());
                return null;
            }
            Instant now = clock.instant();
            activity(run, agent, ActivityType.FAILED, customerText, now);
            notifier.push(run.customerId(), agent.name(), customerText, null, "agent_update", run.runId());
            log.info("run_transition from={} to={} reason={} runId={}", expected, RunState.FAILED, code, run.runId());
            return null;
        });
    }

    private void activity(AgentRun run, PersonalAgent agent, ActivityType type, String message, Instant now) {
        activities.write(run.customerId(), run.agentId(), run.runId(), type, message, Map.of());
    }

    private static String undoLink(PersonalAgent agent, AgentTemplate template, String runId) {
        if (agent.mode() == AgentMode.ACT_THEN_TELL
                && template.action() != null
                && "card.lock".equals(template.action().type())) {
            return "wf://fargo/cards/unlock/" + runId;
        }
        return null;
    }

    private Map<String, String> placeholders(AgentRun run, RunPreview preview, String phase) {
        Map<String, String> values = new LinkedHashMap<>();
        if (run.triggerContext() != null) {
            run.triggerContext().forEach((key, value) -> {
                if (value != null) {
                    values.put(key, String.valueOf(value));
                }
            });
        }
        if (preview != null && preview.effect() != null) {
            values.put("effect", preview.effect());
        }
        if (phase != null) {
            values.put("phase", phase);
        }
        return values;
    }

    private PersonalAgent agent(AgentRun run) {
        PersonalAgent agent = mongoTemplate.findById(run.agentId(), PersonalAgent.class);
        if (agent == null) {
            throw new IllegalStateException("Agent is missing for run " + run.runId());
        }
        return agent;
    }

    private AgentTemplate template(PersonalAgent agent) {
        AgentTemplate template = mongoTemplate.findOne(
                Query.query(Criteria.where("templateId").is(agent.templateId()).and("version").is(agent.templateVersion())),
                AgentTemplate.class);
        if (template == null) {
            throw new IllegalStateException("Template is missing for agent " + agent.agentId());
        }
        return template;
    }

    private RunPreview previewOf(Object quote) {
        if (!(quote instanceof Map<?, ?> raw)) {
            return new RunPreview(null, null, List.of(), Map.of());
        }
        return new RunPreview(text(raw.get("quoteId")), text(raw.get("effect")), strings(raw.get("warnings")), copyMap(raw.get("data")));
    }

    private Map<String, Object> errorOf(Object error) {
        if (!(error instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static Map<String, Object> copyMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> copy = new ArrayList<>();
        for (Object item : raw) {
            if (item != null) {
                copy.add(String.valueOf(item));
            }
        }
        return copy;
    }

    private static RunAttempts attempts(AgentRun run) {
        return run.attempts() == null ? new RunAttempts(0, 0) : run.attempts();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static Duration backoff(int attempts) {
        int shift = Math.min(Math.max(attempts - 1, 0), 30);
        long seconds = 1L << shift;
        return Duration.ofSeconds(Math.min(seconds, Duration.ofMinutes(5).toSeconds()));
    }
}
