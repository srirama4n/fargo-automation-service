package com.wf.fargo.automation.agents;

import com.wf.fargo.automation.agents.AgentViews.AgentDetail;
import com.wf.fargo.automation.agents.AgentViews.AgentSummary;
import com.wf.fargo.automation.agents.AgentViews.CreatedAgent;
import com.wf.fargo.automation.agents.AgentViews.GrantView;
import com.wf.fargo.automation.agents.AgentViews.LastRun;
import com.wf.fargo.automation.agents.AgentViews.RunView;
import com.wf.fargo.automation.agents.AgentViews.StatusChange;
import com.wf.fargo.automation.common.ApiError;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.RawAccountNumbers;
import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.AgentTrigger;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import com.wf.fargo.automation.domain.FireSchedule;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.domain.OutboxDestination;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.RunState;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TriggerSchedule;
import com.wf.fargo.automation.notify.ActivityWriter;
import com.wf.fargo.automation.notify.MessageTemplates;
import com.wf.fargo.automation.outbox.OutboxWriter;
import com.wf.fargo.automation.scheduler.ScheduleCalculator;
import com.wf.fargo.automation.store.ApprovalStore;
import com.wf.fargo.automation.store.PersonalAgentRepository;
import com.wf.fargo.automation.store.RunStateStore;
import com.wf.fargo.automation.store.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final TransactionRunner transactions;
    private final ConfirmationService confirmations;
    private final PolicyChecker policyChecker;
    private final GrantService grants;
    private final TemplateService templates;
    private final ScheduleCalculator scheduleCalculator;
    private final OutboxWriter outbox;
    private final ActivityWriter activities;
    private final AgentControlMessages controls;
    private final PersonalAgentRepository agents;
    private final ApprovalStore approvalStore;
    private final RunStateStore runStateStore;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public AgentService(
            TransactionRunner transactions,
            ConfirmationService confirmations,
            PolicyChecker policyChecker,
            GrantService grants,
            TemplateService templates,
            ScheduleCalculator scheduleCalculator,
            OutboxWriter outbox,
            ActivityWriter activities,
            AgentControlMessages controls,
            PersonalAgentRepository agents,
            ApprovalStore approvalStore,
            RunStateStore runStateStore,
            MongoTemplate mongoTemplate,
            Clock clock) {
        this.transactions = transactions;
        this.confirmations = confirmations;
        this.policyChecker = policyChecker;
        this.grants = grants;
        this.templates = templates;
        this.scheduleCalculator = scheduleCalculator;
        this.outbox = outbox;
        this.activities = activities;
        this.controls = controls;
        this.agents = agents;
        this.approvalStore = approvalStore;
        this.runStateStore = runStateStore;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    public CreatedAgent create(
            String customerId,
            String templateId,
            Integer templateVersion,
            Map<String, Object> params,
            String confirmationToken,
            String sourceUtterance,
            String sessionId) {
        requireText(templateId, "templateId");
        if (templateVersion == null) {
            throw new ApiException(400, "validation_error", "templateVersion is required.");
        }
        if (params == null) {
            throw new ApiException(400, "validation_error", "params is required.");
        }
        requireText(confirmationToken, "confirmationToken");
        requireText(sourceUtterance, "sourceUtterance");
        requireText(sessionId, "sessionId");
        RawAccountNumbers.reject(params);
        ConfirmationService.Verified verified =
                confirmations.verify(customerId, confirmationToken, templateId, templateVersion, params);
        PolicyChecker.PolicyCheck policy = policyChecker.check(customerId, templateId, templateVersion, params);
        rejectPolicy(policy);
        AgentTemplate template = templates.find(templateId, templateVersion).orElseThrow(() -> new ApiException(
                422, "policy_violation", "The agent doesn't meet the rules.",
                List.of(new ApiError("templateId", "template_not_found", "That template is not available."))));
        return transactions.inTransaction(() -> {
            confirmations.consume(verified.confirmationId(), customerId, verified.draftHash());
            recheckCapacity(customerId, template, params);
            Instant now = clock.instant();
            String agentId = "agt_" + Ulids.next(clock.millis());
            List<String> accountScopes = ParamTokens.collect(params);
            List<String> allowedTools = template.requiredTools() == null ? List.of() : List.copyOf(template.requiredTools());
            Grant grant = grants.issue(agentId, customerId, allowedTools, accountScopes, grantLimits(template, params));
            boolean schedule = template.trigger() != null && "schedule".equals(template.trigger().type());
            TriggerSchedule triggerSchedule = schedule ? scheduleOf(params) : null;
            FireSchedule nextFire = null;
            if (schedule) {
                nextFire = new FireSchedule(scheduleCalculator.nextFireAt(
                        triggerSchedule.cron(), triggerSchedule.timezone(), now));
            }
            PersonalAgent agent = new PersonalAgent(
                    agentId,
                    customerId,
                    template.templateId(),
                    template.version(),
                    template.name(),
                    params,
                    new AgentTrigger(
                            template.trigger() == null ? null : template.trigger().type(),
                            accountScopes,
                            filters(params),
                            triggerSchedule),
                    nextFire,
                    null,
                    template.mode(),
                    AgentStatus.ON,
                    grant.grantId(),
                    sourceUtterance,
                    sessionId,
                    null,
                    now,
                    now,
                    1);
            PersonalAgent saved = agents.save(agent);
            long version = saved.version() == null ? 0L : saved.version();
            if (saved.version() == null) {
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(agentId)),
                        new Update().set("version", version),
                        PersonalAgent.class);
            }
            writeActivity(customerId, agentId, null, ActivityType.CREATED, template.name(), now);
            if (!schedule) {
                control(customerId, savedWithVersion(saved, version), "upsert", true);
            }
            return new CreatedAgent(agentId, "on", grant.grantId());
        });
    }

    public List<AgentSummary> list(String callerId, String customerId, String status) {
        if (!callerId.equals(customerId)) {
            log.warn("security=true ownership_mismatch");
            throw notFound();
        }
        Query query = Query.query(Criteria.where("customerId").is(customerId).and("status").in(statuses(status)))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<AgentSummary> summaries = new ArrayList<>();
        for (PersonalAgent agent : mongoTemplate.find(query, PersonalAgent.class)) {
            summaries.add(summary(agent));
        }
        return summaries;
    }

    public AgentDetail detail(String callerId, String agentId) {
        PersonalAgent agent = loadOwned(agentId, callerId);
        Grant grant = mongoTemplate.findOne(Query.query(Criteria.where("agentId").is(agentId)), Grant.class);
        List<AgentRun> runs = mongoTemplate.find(
                Query.query(Criteria.where("agentId").is(agentId))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .limit(10),
                AgentRun.class);
        return new AgentDetail(
                agent.agentId(),
                agent.name(),
                agent.templateId(),
                agent.templateVersion(),
                enumName(agent.mode()),
                enumName(agent.status()),
                effectText(agent),
                agent.params(),
                agent.trigger(),
                agent.guards(),
                grant == null
                        ? new GrantView(List.of(), List.of())
                        : new GrantView(grant.allowedTools(), grant.accountScopes()),
                runs.stream()
                        .map(run -> new RunView(run.runId(), enumName(run.state()), run.createdAt()))
                        .toList(),
                agent.createdAt());
    }

    public long version(String callerId, String agentId) {
        return versionOf(loadOwned(agentId, callerId));
    }

    public StatusChange updateStatus(String callerId, String agentId, String ifMatch, String status) {
        AgentStatus next = switch (status == null ? "" : status) {
            case "paused" -> AgentStatus.PAUSED;
            case "on" -> AgentStatus.ON;
            default -> throw new ApiException(400, "validation_error", "Status must be on or paused.");
        };
        long expected = parseVersion(ifMatch);
        PersonalAgent current = loadOwned(agentId, callerId);
        if (current.status() == AgentStatus.DELETED) {
            throw new ApiException(409, "agent_deleted", "This agent was deleted.");
        }
        if (versionOf(current) != expected) {
            throw new ApiException(409, "version_conflict", "This agent was updated. Refresh and try again.");
        }
        return transactions.inTransaction(() -> {
            Instant now = clock.instant();
            Update update = new Update()
                    .set("status", next)
                    .set("updatedAt", now)
                    .inc("version", 1);
            if (next == AgentStatus.ON && current.trigger() != null && current.trigger().schedule() != null) {
                Instant nextFire = scheduleCalculator.nextFireAt(
                        current.trigger().schedule().cron(), current.trigger().schedule().timezone(), now);
                update.set("schedule.nextFireAt", nextFire);
            }
            PersonalAgent updated = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id")
                            .is(agentId)
                            .and("customerId")
                            .is(callerId)
                            .and("version")
                            .is(expected)
                            .and("status")
                            .ne(AgentStatus.DELETED)),
                    update,
                    FindAndModifyOptions.options().returnNew(true),
                    PersonalAgent.class);
            if (updated == null) {
                PersonalAgent latest = agents.findById(agentId).orElse(null);
                if (latest != null && latest.status() == AgentStatus.DELETED) {
                    throw new ApiException(409, "agent_deleted", "This agent was deleted.");
                }
                throw new ApiException(409, "version_conflict", "This agent was updated. Refresh and try again.");
            }
            ActivityType type = next == AgentStatus.PAUSED ? ActivityType.PAUSED : ActivityType.RESUMED;
            writeActivity(callerId, agentId, null, type, updated.name(), now);
            control(callerId, updated, next == AgentStatus.PAUSED ? "remove" : "upsert", false);
            return new StatusChange(enumName(next), versionOf(updated));
        });
    }

    public void delete(String callerId, String agentId) {
        PersonalAgent current = loadOwned(agentId, callerId);
        if (current.status() == AgentStatus.DELETED) {
            throw new ApiException(409, "agent_deleted", "This agent was deleted.");
        }
        transactions.inTransaction(() -> {
            Instant now = clock.instant();
            PersonalAgent deleted = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id")
                            .is(agentId)
                            .and("customerId")
                            .is(callerId)
                            .and("status")
                            .ne(AgentStatus.DELETED)),
                    new Update().set("status", AgentStatus.DELETED).set("updatedAt", now).inc("version", 1),
                    FindAndModifyOptions.options().returnNew(true),
                    PersonalAgent.class);
            if (deleted == null) {
                throw new ApiException(409, "agent_deleted", "This agent was deleted.");
            }
            grants.revoke(agentId, "agent_deleted");
            List<Approval> pending = mongoTemplate.find(
                    Query.query(Criteria.where("agentId").is(agentId).and("status").is(ApprovalStatus.PENDING)),
                    Approval.class);
            for (Approval approval : pending) {
                approvalStore.decide(approval.approvalId(), ApprovalStatus.PENDING, ApprovalStatus.EXPIRED, null);
                AgentRun run = mongoTemplate.findById(approval.runId(), AgentRun.class);
                if (run != null && run.state() == RunState.AWAITING_APPROVAL) {
                    runStateStore.transition(
                            run.runId(), RunState.AWAITING_APPROVAL, RunState.EXPIRED, "agent_deleted", null);
                }
            }
            writeActivity(callerId, agentId, null, ActivityType.DELETED, deleted.name(), now);
            control(callerId, deleted, "remove", false);
            return null;
        });
    }

    private void recheckCapacity(String customerId, AgentTemplate template, Map<String, Object> params) {
        PolicyChecker.PolicyCheck again =
                policyChecker.check(customerId, template.templateId(), template.version(), params);
        List<ApiError> blocking = again.errors().stream()
                .filter(error -> "agent_limit_reached".equals(error.code()) || "duplicate_agent".equals(error.code()))
                .toList();
        if (blocking.stream().anyMatch(error -> "agent_limit_reached".equals(error.code()))
                && blocking.stream().allMatch(error -> "agent_limit_reached".equals(error.code()))) {
            throw new ApiException(409, "agent_limit_reached", blocking.get(0).message());
        }
        if (!blocking.isEmpty()) {
            throw new ApiException(422, "policy_violation", "The agent doesn't meet the rules.", blocking);
        }
    }

    private void rejectPolicy(PolicyChecker.PolicyCheck policy) {
        if (policy.errors().isEmpty()) {
            return;
        }
        boolean onlyLimit = policy.errors().stream().allMatch(error -> "agent_limit_reached".equals(error.code()));
        if (onlyLimit) {
            throw new ApiException(409, "agent_limit_reached", policy.errors().get(0).message());
        }
        throw new ApiException(422, "policy_violation", "The agent doesn't meet the rules.", policy.errors());
    }

    private PersonalAgent loadOwned(String agentId, String customerId) {
        PersonalAgent agent = agents.findById(agentId).orElse(null);
        if (agent == null) {
            throw notFound();
        }
        if (!customerId.equals(agent.customerId())) {
            log.warn("security=true ownership_mismatch agentId={}", agentId);
            throw notFound();
        }
        return agent;
    }

    private AgentSummary summary(PersonalAgent agent) {
        AgentRun latest = mongoTemplate.findOne(
                Query.query(Criteria.where("agentId").is(agent.agentId()))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .limit(1),
                AgentRun.class);
        LastRun lastRun = latest == null ? null : new LastRun(enumName(latest.state()), latest.updatedAt());
        return new AgentSummary(
                agent.agentId(),
                agent.name(),
                agent.templateId(),
                enumName(agent.mode()),
                enumName(agent.status()),
                effectText(agent),
                agent.createdAt(),
                lastRun);
    }

    private String effectText(PersonalAgent agent) {
        return templates.find(agent.templateId(), agent.templateVersion())
                .map(AgentTemplate::effectText)
                .orElse(null);
    }

    private void writeActivity(
            String customerId, String agentId, String runId, ActivityType type, String agentName, Instant now) {
        activities.write(customerId, agentId, runId, type, MessageTemplates.render(type, agentName), Map.of());
    }

    private void control(String customerId, PersonalAgent agent, String op, boolean backfill) {
        outbox.write(OutboxDestination.AGENT_CONTROL, customerId, controls.message(customerId, agent, op, backfill));
    }

    private static PersonalAgent savedWithVersion(PersonalAgent saved, long version) {
        return saved.version() == null ? saved.withVersion(version) : saved;
    }

    private static TriggerSchedule scheduleOf(Map<String, Object> params) {
        Object cron = params.get("cron");
        Object timezone = params.get("timezone");
        if (!(cron instanceof String cronText) || cronText.isBlank()
                || !(timezone instanceof String zone) || zone.isBlank()) {
            throw new ApiException(422, "policy_violation", "The agent doesn't meet the rules.",
                    List.of(new ApiError("params.cron", "invalid_param", "Check this value and try again.")));
        }
        return new TriggerSchedule(cronText, zone);
    }

    private static Map<String, Object> filters(Map<String, Object> params) {
        Map<String, Object> filters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof String text && ParamTokens.TOKEN.matcher(text).matches()) {
                continue;
            }
            filters.put(entry.getKey(), entry.getValue());
        }
        return filters;
    }

    private static Map<String, Object> grantLimits(AgentTemplate template, Map<String, Object> params) {
        Map<String, Object> limits = new LinkedHashMap<>();
        TemplateLimits templateLimits = template.limits();
        if (templateLimits != null) {
            if (templateLimits.maxAmount() != null) {
                limits.put("maxAmount", templateLimits.maxAmount());
            }
            if (templateLimits.maxRunsPerDay() != null) {
                limits.put("maxRunsPerDay", templateLimits.maxRunsPerDay());
            }
            if (templateLimits.maxRunsPerPeriod() != null) {
                limits.put("maxRunsPerPeriod", templateLimits.maxRunsPerPeriod());
            }
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains("amount") && entry.getValue() instanceof Number) {
                limits.put(entry.getKey(), entry.getValue());
            }
        }
        return limits;
    }

    private static List<AgentStatus> statuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of(AgentStatus.ON, AgentStatus.PAUSED);
        }
        List<AgentStatus> parsed = new ArrayList<>();
        for (String part : raw.split(",")) {
            parsed.add(switch (part.trim()) {
                case "on" -> AgentStatus.ON;
                case "paused" -> AgentStatus.PAUSED;
                case "deleted" -> AgentStatus.DELETED;
                default -> throw new ApiException(400, "validation_error", "Status must be on, paused, or deleted.");
            });
        }
        return parsed;
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(400, "validation_error", "If-Match header is required.");
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new ApiException(400, "validation_error", "If-Match header is required.");
        }
    }

    private static long versionOf(PersonalAgent agent) {
        return agent.version() == null ? 0L : agent.version();
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, "validation_error", field + " is required.");
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, "not_found", "Not found.");
    }
}
