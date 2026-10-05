package com.wf.fargo.automation.sweeper;

import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.dispatch.TaskDispatcher;
import com.wf.fargo.automation.dispatch.TaskFactory;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunAttempts;
import com.wf.fargo.automation.domain.RunError;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.notify.ActivityWriter;
import com.wf.fargo.automation.notify.MessageTemplates;
import com.wf.fargo.automation.notify.Notifier;
import com.wf.fargo.automation.observe.AutomationMetrics;
import com.wf.fargo.automation.outbox.OutboxWriter;
import com.wf.fargo.automation.store.RunStateStore;
import com.wf.fargo.automation.store.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StuckRunJob {

    private static final Logger log = LoggerFactory.getLogger(StuckRunJob.class);
    private static final int MAX_ATTEMPTS = 3;

    private final MongoTemplate mongoTemplate;
    private final TransactionRunner transactions;
    private final RunStateStore runs;
    private final OutboxWriter outbox;
    private final ActivityWriter activities;
    private final Notifier notifier;
    private final TaskFactory tasks;
    private final TaskDispatcher dispatcher;
    private final Clock clock;
    private final AppProperties properties;
    private final AutomationMetrics metrics;

    public StuckRunJob(
            MongoTemplate mongoTemplate,
            TransactionRunner transactions,
            RunStateStore runs,
            OutboxWriter outbox,
            ActivityWriter activities,
            Notifier notifier,
            TaskFactory tasks,
            TaskDispatcher dispatcher,
            Clock clock,
            AppProperties properties,
            AutomationMetrics metrics) {
        this.mongoTemplate = mongoTemplate;
        this.transactions = transactions;
        this.runs = runs;
        this.outbox = outbox;
        this.activities = activities;
        this.notifier = notifier;
        this.tasks = tasks;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${automation.sweeper.stuckRunCron}")
    @SchedulerLock(name = "stuckRuns", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void sweep() {
        recover();
    }

    public int recover() {
        Instant deadline = clock.instant().minus(properties.limits().stuckRunTimeout());
        List<AgentRun> stuck = mongoTemplate.find(
                Query.query(Criteria.where("state")
                        .in(RunState.PREVIEWING, RunState.EXECUTING)
                        .and("updatedAt")
                        .lt(deadline)),
                AgentRun.class);
        int handled = 0;
        for (AgentRun run : stuck) {
            if (handle(run)) {
                handled++;
            }
        }
        return handled;
    }

    private boolean handle(AgentRun run) {
        boolean execute = run.state() == RunState.EXECUTING;
        String phase = execute ? "execute" : "preview";
        RunAttempts attempts = attempts(run);
        int used = execute ? attempts.execute() : attempts.preview();
        if (used < MAX_ATTEMPTS) {
            return retry(run, phase, attempts, used + 1);
        }
        String code = execute ? "execute_timeout" : "preview_timeout";
        boolean failed = fail(run, phase, code);
        if (failed && execute) {
            log.error("execute_timeout runId={}", run.runId());
        }
        return failed;
    }

    private boolean retry(AgentRun run, String phase, RunAttempts attempts, int nextAttempt) {
        PersonalAgent agent = agent(run);
        AgentTemplate template = template(agent);
        String newTaskId = "tsk_" + Ulids.next(clock.millis());
        RunAttempts updatedAttempts = "execute".equals(phase)
                ? new RunAttempts(attempts.preview(), nextAttempt)
                : new RunAttempts(nextAttempt, attempts.execute());
        String quoteId = run.preview() == null ? null : run.preview().quoteId();
        Map<String, Object> payload = "execute".equals(phase)
                ? tasks.execute(run, agent, template, newTaskId, quoteId)
                : tasks.preview(run, agent, template, newTaskId);
        Boolean sent = transactions.inTransaction(() -> {
            AgentRun claimed = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id")
                            .is(run.runId())
                            .and("state")
                            .is(run.state())
                            .and("currentTaskId")
                            .is(run.currentTaskId())
                            .and("updatedAt")
                            .is(run.updatedAt())),
                    new Update()
                            .set("currentTaskId", newTaskId)
                            .set("attempts", updatedAttempts)
                            .set("updatedAt", clock.instant()),
                    FindAndModifyOptions.options().returnNew(true),
                    AgentRun.class);
            if (claimed == null) {
                return false;
            }
            outbox.write(dispatcher.destination(), run.customerId(), payload, clock.instant());
            return true;
        });
        if (Boolean.TRUE.equals(sent)) {
            metrics.stuck(run.state(), "retried");
            log.info("stuck_retry phase={} attempt={} runId={}", phase, nextAttempt, run.runId());
            return true;
        }
        return false;
    }

    private boolean fail(AgentRun run, String phase, String code) {
        PersonalAgent agent = agent(run);
        RunState expected = "execute".equals(phase) ? RunState.EXECUTING : RunState.PREVIEWING;
        String customerText = MessageTemplates.render(ActivityType.FAILED, agent.name(), Map.of("phase", phase));
        Boolean failed = transactions.inTransaction(() -> {
            if (runs.transition(
                            run.runId(),
                            expected,
                            RunState.FAILED,
                            code,
                            update -> update.set("error", new RunError(code, code)),
                            run.currentTaskId())
                    .isEmpty()) {
                return false;
            }
            activities.write(run.customerId(), agent.agentId(), run.runId(), ActivityType.FAILED, customerText, Map.of());
            notifier.push(run.customerId(), agent.name(), customerText, null, "agent_update", run.runId());
            log.info("run_transition from={} to={} reason={} runId={}", expected, RunState.FAILED, code, run.runId());
            return true;
        });
        if (Boolean.TRUE.equals(failed)) {
            metrics.stuck(expected, "failed");
        }
        return Boolean.TRUE.equals(failed);
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

    private static RunAttempts attempts(AgentRun run) {
        return run.attempts() == null ? new RunAttempts(0, 0) : run.attempts();
    }
}
