package com.wf.fargo.automation.scheduler;

import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.domain.TriggerSchedule;
import com.wf.fargo.automation.runs.RunService;
import com.wf.fargo.automation.runs.TriggerFired;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduleTriggerJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTriggerJob.class);

    private final MongoTemplate mongoTemplate;
    private final ScheduleCalculator calculator;
    private final RunService runs;
    private final Clock clock;
    private final AppProperties properties;

    public ScheduleTriggerJob(
            MongoTemplate mongoTemplate,
            ScheduleCalculator calculator,
            RunService runs,
            Clock clock,
            AppProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.calculator = calculator;
        this.runs = runs;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${automation.scheduler.pollInterval}")
    public int tick() {
        int batch = properties.scheduler().batchSize();
        Instant now = clock.instant();
        int fired = 0;
        Set<String> skipped = new HashSet<>();
        while (fired < batch && skipped.size() < batch) {
            PersonalAgent due = findDue(now, skipped);
            if (due == null || due.schedule() == null || due.schedule().nextFireAt() == null) {
                break;
            }
            Instant occurrence = due.schedule().nextFireAt();
            TriggerSchedule schedule = due.trigger() == null ? null : due.trigger().schedule();
            if (schedule == null || schedule.cron() == null || schedule.timezone() == null) {
                log.warn("schedule_missing agentId={}", due.agentId());
                skipped.add(due.agentId());
                continue;
            }
            Instant next = calculator.nextFireAt(schedule.cron(), schedule.timezone(), now);
            if (next == null || !next.isAfter(now)) {
                log.warn("schedule_next_missing agentId={}", due.agentId());
                skipped.add(due.agentId());
                continue;
            }
            if (claim(due, occurrence, next, now) == null) {
                skipped.add(due.agentId());
                continue;
            }
            runs.start(fired(due, occurrence));
            log.info("schedule_fire agentId={} occurrence={}", due.agentId(), occurrence);
            fired++;
        }
        return fired;
    }

    private PersonalAgent findDue(Instant now, Set<String> skipped) {
        Criteria criteria = Criteria.where("status").is(AgentStatus.ON).and("schedule.nextFireAt").lte(now);
        if (!skipped.isEmpty()) {
            criteria = criteria.and("_id").nin(skipped);
        }
        Query query = Query.query(criteria).with(Sort.by(Sort.Direction.ASC, "schedule.nextFireAt"));
        return mongoTemplate.findOne(query, PersonalAgent.class);
    }

    private PersonalAgent claim(PersonalAgent due, Instant occurrence, Instant next, Instant now) {
        Query query = Query.query(Criteria.where("_id")
                .is(due.agentId())
                .and("status")
                .is(AgentStatus.ON)
                .and("version")
                .is(due.version())
                .and("schedule.nextFireAt")
                .is(occurrence));
        Update update = new Update()
                .set("schedule.nextFireAt", next)
                .set("updatedAt", now)
                .inc("version", 1);
        return mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(false), PersonalAgent.class);
    }

    private TriggerFired fired(PersonalAgent agent, Instant occurrence) {
        String instant = occurrence.toString();
        return new TriggerFired(
                1,
                Ulids.next(clock.millis()),
                occurrence,
                agent.customerId(),
                agent.agentId(),
                instant,
                "schedule:" + instant,
                "schedule",
                Map.of());
    }
}
