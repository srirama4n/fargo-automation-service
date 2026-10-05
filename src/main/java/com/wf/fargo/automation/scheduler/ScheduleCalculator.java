package com.wf.fargo.automation.scheduler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
public class ScheduleCalculator {

    public Instant nextFireAt(String cron, String timezone, Instant after) {
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime next = expression.next(after.atZone(ZoneId.of(timezone)));
        return next == null ? null : next.toInstant();
    }
}
