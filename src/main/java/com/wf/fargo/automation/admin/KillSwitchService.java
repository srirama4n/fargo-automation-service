package com.wf.fargo.automation.admin;

import com.wf.fargo.automation.admin.AdminViews.JobView;
import com.wf.fargo.automation.agents.AgentControlMessages;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AdminJob;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.notify.ActivityWriter;
import com.wf.fargo.automation.notify.MessageTemplates;
import com.wf.fargo.automation.notify.Notifier;
import com.wf.fargo.automation.outbox.OutboxWriter;
import com.wf.fargo.automation.store.ApprovalStore;
import com.wf.fargo.automation.store.RunStateStore;
import com.wf.fargo.automation.store.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class KillSwitchService {

    static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);

    private final MongoTemplate mongoTemplate;
    private final TransactionRunner transactions;
    private final OutboxWriter outbox;
    private final ActivityWriter activities;
    private final Notifier notifier;
    private final AgentControlMessages controls;
    private final ApprovalStore approvals;
    private final RunStateStore runs;
    private final Clock clock;

    public KillSwitchService(
            MongoTemplate mongoTemplate,
            TransactionRunner transactions,
            OutboxWriter outbox,
            ActivityWriter activities,
            Notifier notifier,
            AgentControlMessages controls,
            ApprovalStore approvals,
            RunStateStore runs,
            Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.transactions = transactions;
        this.outbox = outbox;
        this.activities = activities;
        this.notifier = notifier;
        this.controls = controls;
        this.approvals = approvals;
        this.runs = runs;
        this.clock = clock;
    }

    public JobView pauseAll(String adminId, String templateId, String reason, boolean expirePending) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, "validation_error", "reason is required.");
        }
        String scopedTemplate = templateId == null || templateId.isBlank() ? null : templateId;
        Instant now = clock.instant();
        String jobId = "job_" + Ulids.next(clock.millis());
        mongoTemplate.insert(new AdminJob(
                jobId, scopedTemplate, reason, expirePending, adminId, 0, 0, "running", now, now, 1));
        while (true) {
            List<PersonalAgent> due = findDue(scopedTemplate);
            if (due.isEmpty()) {
                break;
            }
            int paused = transactions.inTransaction(() -> pauseBatch(due, adminId, reason, expirePending));
            if (paused == 0) {
                break;
            }
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(jobId)),
                    new Update().inc("paused", paused).inc("batches", 1).set("updatedAt", clock.instant()),
                    AdminJob.class);
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(jobId)),
                new Update().set("status", "completed").set("updatedAt", clock.instant()),
                AdminJob.class);
        AdminJob job = mongoTemplate.findById(jobId, AdminJob.class);
        log.info(
                "kill_switch admin={} reason={} jobId={} paused={} batches={}",
                adminId,
                reason,
                jobId,
                job.paused(),
                job.batches());
        return view(job);
    }

    public JobView job(String jobId) {
        AdminJob job = mongoTemplate.findById(jobId, AdminJob.class);
        if (job == null) {
            throw new ApiException(404, "not_found", "Not found.");
        }
        return view(job);
    }

    private List<PersonalAgent> findDue(String templateId) {
        Criteria criteria = Criteria.where("status").is(AgentStatus.ON);
        if (templateId != null) {
            criteria = criteria.and("templateId").is(templateId);
        }
        return mongoTemplate.find(
                Query.query(criteria).with(Sort.by(Sort.Direction.ASC, "_id")).limit(BATCH_SIZE), PersonalAgent.class);
    }

    private int pauseBatch(List<PersonalAgent> due, String adminId, String reason, boolean expirePending) {
        Instant now = clock.instant();
        List<PersonalAgent> paused = new ArrayList<>();
        for (PersonalAgent agent : due) {
            PersonalAgent updated = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id").is(agent.agentId()).and("status").is(AgentStatus.ON)),
                    new Update().set("status", AgentStatus.PAUSED).set("updatedAt", now).inc("version", 1),
                    FindAndModifyOptions.options().returnNew(true),
                    PersonalAgent.class);
            if (updated != null) {
                paused.add(updated);
            }
        }
        Map<String, Object> data = Map.of("admin", adminId, "reason", reason);
        for (PersonalAgent agent : paused) {
            activities.write(
                    agent.customerId(),
                    agent.agentId(),
                    null,
                    ActivityType.PAUSED,
                    MessageTemplates.render(ActivityType.PAUSED, agent.name()),
                    data);
            outbox.write(
                    OutboxDestination.AGENT_CONTROL,
                    agent.customerId(),
                    controls.message(agent.customerId(), agent, "remove", false));
        }
        if (expirePending) {
            expirePending(paused);
        }
        return paused.size();
    }

    private void expirePending(List<PersonalAgent> paused) {
        List<String> ids = paused.stream().map(PersonalAgent::agentId).toList();
        if (ids.isEmpty()) {
            return;
        }
        List<Approval> pending = mongoTemplate.find(
                Query.query(Criteria.where("agentId").in(ids).and("status").is(ApprovalStatus.PENDING)), Approval.class);
        for (Approval approval : pending) {
            AgentRun run = mongoTemplate.findById(approval.runId(), AgentRun.class);
            if (approvals.decide(approval.approvalId(), ApprovalStatus.PENDING, ApprovalStatus.EXPIRED,
                            update -> update.set("decidedAt", clock.instant()))
                    .isEmpty()) {
                continue;
            }
            if (run == null || run.state() != RunState.AWAITING_APPROVAL) {
                continue;
            }
            if (runs.transition(run.runId(), RunState.AWAITING_APPROVAL, RunState.EXPIRED, "kill_switch", null)
                    .isEmpty()) {
                throw new IllegalStateException("Run " + run.runId() + " left AWAITING_APPROVAL during kill switch");
            }
            PersonalAgent agent = paused.stream()
                    .filter(item -> item.agentId().equals(approval.agentId()))
                    .findFirst()
                    .orElse(null);
            String name = agent == null ? null : agent.name();
            String message = MessageTemplates.render(ActivityType.EXPIRED, name);
            activities.write(run.customerId(), run.agentId(), run.runId(), ActivityType.EXPIRED, message, Map.of("reason", "kill_switch"));
            notifier.push(run.customerId(), name == null ? "This agent" : name, message, null, "agent_update", run.runId());
            log.info(
                    "run_transition from={} to={} reason={} runId={}",
                    RunState.AWAITING_APPROVAL,
                    RunState.EXPIRED,
                    "kill_switch",
                    run.runId());
        }
    }

    private static JobView view(AdminJob job) {
        return new JobView(job.jobId(), job.status(), job.paused(), job.batches());
    }
}
