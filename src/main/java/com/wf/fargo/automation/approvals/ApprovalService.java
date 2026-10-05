package com.wf.fargo.automation.approvals;

import com.wf.fargo.automation.approvals.ApprovalViews.AgentName;
import com.wf.fargo.automation.approvals.ApprovalViews.ApprovalView;
import com.wf.fargo.automation.approvals.ApprovalViews.PreviewView;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.CanonicalJson;
import com.wf.fargo.automation.common.Hashing;
import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.dispatch.TaskDispatcher;
import com.wf.fargo.automation.dispatch.TaskFactory;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunPreview;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.notify.ActivityWriter;
import com.wf.fargo.automation.notify.MessageTemplates;
import com.wf.fargo.automation.notify.Notifier;
import com.wf.fargo.automation.observe.AutomationMetrics;
import com.wf.fargo.automation.observe.RunTrace;
import com.wf.fargo.automation.outbox.OutboxWriter;
import com.wf.fargo.automation.store.ApprovalStore;
import com.wf.fargo.automation.store.RunStateStore;
import com.wf.fargo.automation.store.TransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final String NOT_PENDING = "This approval can no longer be decided.";

    private final MongoTemplate mongoTemplate;
    private final ApprovalStore approvals;
    private final RunStateStore runs;
    private final TransactionRunner transactions;
    private final OutboxWriter outbox;
    private final ActivityWriter activities;
    private final Notifier notifier;
    private final TaskFactory tasks;
    private final TaskDispatcher dispatcher;
    private final StepUpVerifier stepUp;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RunTrace traces;
    private final AutomationMetrics metrics;

    public ApprovalService(
            MongoTemplate mongoTemplate,
            ApprovalStore approvals,
            RunStateStore runs,
            TransactionRunner transactions,
            OutboxWriter outbox,
            ActivityWriter activities,
            Notifier notifier,
            TaskFactory tasks,
            TaskDispatcher dispatcher,
            StepUpVerifier stepUp,
            ObjectMapper objectMapper,
            Clock clock,
            RunTrace traces,
            AutomationMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.approvals = approvals;
        this.runs = runs;
        this.transactions = transactions;
        this.outbox = outbox;
        this.activities = activities;
        this.notifier = notifier;
        this.tasks = tasks;
        this.dispatcher = dispatcher;
        this.stepUp = stepUp;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.traces = traces;
        this.metrics = metrics;
    }

    public ApprovalView get(String customerId, String approvalId) {
        Approval approval = loadOwned(customerId, approvalId);
        AgentRun run = run(approval.runId());
        PersonalAgent agent = agent(run);
        RunPreview preview = run.preview();
        return new ApprovalView(
                approval.approvalId(),
                approval.status().name().toLowerCase(Locale.ROOT),
                approval.expiresAt(),
                new AgentName(agent.name()),
                copy(run.triggerContext()),
                new PreviewView(
                        preview == null ? null : preview.effect(),
                        preview == null || preview.warnings() == null ? List.of() : List.copyOf(preview.warnings()),
                        preview == null ? Map.of() : copy(preview.data())));
    }

    public Decision approve(String customerId, String approvalId, String token) {
        Approval approval = loadOwned(customerId, approvalId);
        if (approval.status() != ApprovalStatus.PENDING
                || approval.expiresAt() == null
                || !approval.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(409, "approval_not_pending", NOT_PENDING);
        }
        String stepUpRef = stepUp.verify(token, customerId, approvalId);
        AgentRun run = run(approval.runId());
        if (!previewHash(run.preview()).equals(approval.previewHash())) {
            log.warn("security=true preview_changed");
            throw new ApiException(409, "preview_changed", "The preview changed. Approve the new preview.");
        }
        PersonalAgent agent = agent(run);
        AgentTemplate template = template(agent);
        String taskId = "tsk_" + Ulids.next(clock.millis());
        RunAttempts attempts = run.attempts() == null ? new RunAttempts(0, 0) : run.attempts();
        String quoteId = run.preview() == null ? null : run.preview().quoteId();
        try (var ignored = traces.scope(run.traceId(), run.customerId(), run.agentId(), run.runId())) {
            transactions.inTransaction(() -> {
            if (approvals.decide(approvalId, ApprovalStatus.PENDING, ApprovalStatus.APPROVED, update -> {
                        update.set("stepUpRef", stepUpRef);
                        update.set("decidedAt", clock.instant());
                    })
                    .isEmpty()) {
                throw new ApiException(409, "approval_not_pending", NOT_PENDING);
            }
            if (runs.transition(run.runId(), RunState.AWAITING_APPROVAL, RunState.EXECUTING, "approved", update -> {
                        update.set("currentTaskId", taskId);
                        update.set("attempts", new RunAttempts(attempts.preview(), attempts.execute() + 1));
                    })
                    .isEmpty()) {
                throw new ApiException(409, "approval_not_pending", NOT_PENDING);
            }
            outbox.write(dispatcher.destination(), run.customerId(), tasks.execute(run, agent, template, taskId, quoteId));
            activity(run, agent, ActivityType.APPROVED);
            log.info(
                    "run_transition from={} to={} reason={} runId={}",
                    RunState.AWAITING_APPROVAL,
                    RunState.EXECUTING,
                    "approved",
                    run.runId());
            return null;
            });
        }
        metrics.approvalDecided("approved", template.templateId(), elapsed(approval));
        return new Decision("approved", "executing");
    }

    public String deny(String customerId, String approvalId) {
        Approval approval = loadOwned(customerId, approvalId);
        if (approval.status() != ApprovalStatus.PENDING) {
            throw new ApiException(409, "approval_not_pending", NOT_PENDING);
        }
        AgentRun run = run(approval.runId());
        PersonalAgent agent = agent(run);
        AgentTemplate template = template(agent);
        try (var ignored = traces.scope(run.traceId(), run.customerId(), run.agentId(), run.runId())) {
            transactions.inTransaction(() -> {
            if (approvals.decide(approvalId, ApprovalStatus.PENDING, ApprovalStatus.DENIED,
                            update -> update.set("decidedAt", clock.instant()))
                    .isEmpty()) {
                throw new ApiException(409, "approval_not_pending", NOT_PENDING);
            }
            if (runs.transition(run.runId(), RunState.AWAITING_APPROVAL, RunState.DENIED, "denied", null)
                    .isEmpty()) {
                throw new ApiException(409, "approval_not_pending", NOT_PENDING);
            }
            activity(run, agent, ActivityType.DENIED);
            baseline(run, template, "approval_denied");
            log.info(
                    "run_transition from={} to={} reason={} runId={}",
                    RunState.AWAITING_APPROVAL,
                    RunState.DENIED,
                    "denied",
                    run.runId());
            return null;
            });
        }
        metrics.approvalDecided("denied", template.templateId(), elapsed(approval));
        return "denied";
    }

    public int expireDue() {
        List<Approval> due = mongoTemplate.find(
                Query.query(Criteria.where("status").is(ApprovalStatus.PENDING).and("expiresAt").lte(clock.instant())),
                Approval.class);
        int expired = 0;
        for (Approval approval : due) {
            try {
                if (expire(approval)) {
                    expired++;
                }
            } catch (ApiException exception) {
                log.info("approval_expire_skipped approvalId={}", approval.approvalId());
            }
        }
        return expired;
    }

    private boolean expire(Approval approval) {
        AgentRun run = mongoTemplate.findById(approval.runId(), AgentRun.class);
        if (run == null) {
            return false;
        }
        PersonalAgent agent = agent(run);
        AgentTemplate template = template(agent);
        Boolean done;
        try (var ignored = traces.scope(run.traceId(), run.customerId(), run.agentId(), run.runId())) {
            done = transactions.inTransaction(() -> {
            if (approvals.decide(approval.approvalId(), ApprovalStatus.PENDING, ApprovalStatus.EXPIRED,
                            update -> update.set("decidedAt", clock.instant()))
                    .isEmpty()) {
                return false;
            }
            if (runs.transition(run.runId(), RunState.AWAITING_APPROVAL, RunState.EXPIRED, "expired", null)
                    .isEmpty()) {
                throw new ApiException(409, "approval_not_pending", NOT_PENDING);
            }
            String expired = MessageTemplates.render(ActivityType.EXPIRED, agent.name());
            activity(run, agent, ActivityType.EXPIRED, expired);
            notifier.push(run.customerId(), agent.name(), expired, null, "agent_update", run.runId());
            baseline(run, template, "approval_expired");
            log.info(
                    "run_transition from={} to={} reason={} runId={}",
                    RunState.AWAITING_APPROVAL,
                    RunState.EXPIRED,
                    "expired",
                    run.runId());
            return true;
            });
        }
        if (Boolean.TRUE.equals(done)) {
            metrics.approvalDecided("expired", template.templateId(), elapsed(approval));
        }
        return Boolean.TRUE.equals(done);
    }

    private Duration elapsed(Approval approval) {
        if (approval.createdAt() == null) {
            return null;
        }
        return Duration.between(approval.createdAt(), clock.instant());
    }

    private void baseline(AgentRun run, AgentTemplate template, String reason) {
        if (template.trigger() == null || !"recurring_charge_increased".equals(template.trigger().type())) {
            return;
        }
        Map<String, Object> context = run.triggerContext() == null ? Map.of() : run.triggerContext();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("messageId", Ulids.next(clock.millis()));
        body.put("occurredAt", clock.instant().toString());
        body.put("customerId", run.customerId());
        body.put("cardToken", context.get("cardToken"));
        body.put("merchantId", context.get("merchantId"));
        body.put("amount", context.get("newAmount"));
        body.put("reason", reason);
        outbox.write(OutboxDestination.BASELINE_UPDATES, run.customerId(), body);
    }

    private void activity(AgentRun run, PersonalAgent agent, ActivityType type) {
        activity(run, agent, type, MessageTemplates.render(type, agent.name()));
    }

    private void activity(AgentRun run, PersonalAgent agent, ActivityType type, String message) {
        activities.write(run.customerId(), run.agentId(), run.runId(), type, message, Map.of());
    }

    private String previewHash(RunPreview preview) {
        if (preview == null) {
            return "";
        }
        return Hashing.sha256Prefixed(CanonicalJson.of(objectMapper.valueToTree(preview)));
    }

    private Approval loadOwned(String customerId, String approvalId) {
        Approval approval = mongoTemplate.findById(approvalId, Approval.class);
        if (approval == null) {
            throw new ApiException(404, "not_found", "Not found.");
        }
        if (approval.customerId() == null || !approval.customerId().equals(customerId)) {
            log.warn("security=true ownership_mismatch");
            throw new ApiException(404, "not_found", "Not found.");
        }
        return approval;
    }

    private AgentRun run(String runId) {
        AgentRun run = mongoTemplate.findById(runId, AgentRun.class);
        if (run == null) {
            throw new ApiException(404, "not_found", "Not found.");
        }
        return run;
    }

    private PersonalAgent agent(AgentRun run) {
        PersonalAgent agent = mongoTemplate.findById(run.agentId(), PersonalAgent.class);
        if (agent == null) {
            throw new ApiException(404, "not_found", "Not found.");
        }
        return agent;
    }

    private AgentTemplate template(PersonalAgent agent) {
        AgentTemplate template = mongoTemplate.findOne(
                Query.query(Criteria.where("templateId").is(agent.templateId()).and("version").is(agent.templateVersion())),
                AgentTemplate.class);
        if (template == null) {
            throw new ApiException(404, "not_found", "Not found.");
        }
        return template;
    }

    private static Map<String, Object> copy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(values);
    }

    public record Decision(String status, String runState) {}
}
