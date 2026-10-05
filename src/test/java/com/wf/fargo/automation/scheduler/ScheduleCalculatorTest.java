package com.wf.fargo.automation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScheduleCalculatorTest {

    private static final String NEW_YORK = "America/New_York";

    private final ScheduleCalculator calculator = new ScheduleCalculator();

    @Test
    void dailyNineAmSkipsTheSpringForwardHour() {
        Instant afterSaturday = Instant.parse("2026-03-07T14:00:00Z");
        Instant sunday = calculator.nextFireAt("0 0 9 * * *", NEW_YORK, afterSaturday);

        assertThat(sunday).isEqualTo(Instant.parse("2026-03-08T13:00:00Z"));
        assertThat(calculator.nextFireAt("0 0 9 * * *", NEW_YORK, sunday))
                .isEqualTo(Instant.parse("2026-03-09T13:00:00Z"));
    }

    @Test
    void dailyNineAmUsesStandardTimeAfterTheFallBack() {
        Instant afterSaturday = Instant.parse("2026-10-31T13:00:00Z");
        Instant sunday = calculator.nextFireAt("0 0 9 * * *", NEW_YORK, afterSaturday);

        assertThat(sunday).isEqualTo(Instant.parse("2026-11-01T14:00:00Z"));
        assertThat(calculator.nextFireAt("0 0 9 * * *", NEW_YORK, sunday))
                .isAfter(sunday);
    }

    @Test
    void twoThirtySkipsTheMissingHourAndKeepsMovingForward() {
        Instant after = Instant.parse("2026-03-07T07:30:00Z");
        Instant next = calculator.nextFireAt("0 30 2 * * *", NEW_YORK, after);
        Instant following = calculator.nextFireAt("0 30 2 * * *", NEW_YORK, next);

        assertThat(next).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
        assertThat(following).isAfter(next);
    }

    @Test
    void oneThirtyOverlapAdvancesOnEveryCall() {
        Instant cursor = Instant.parse("2026-10-31T05:30:00Z");
        Instant first = calculator.nextFireAt("0 30 1 * * *", NEW_YORK, cursor);
        Instant second = calculator.nextFireAt("0 30 1 * * *", NEW_YORK, first);
        Instant third = calculator.nextFireAt("0 30 1 * * *", NEW_YORK, second);

        assertThat(first).isAfter(cursor);
        assertThat(second).isAfter(first);
        assertThat(third).isAfter(second);
    }
}
