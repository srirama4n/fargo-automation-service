package com.wf.fargo.automation.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.domain.ActivityType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTemplatesTest {

    @Test
    void everyActivityTypeRendersWithoutPlaceholdersLeft() {
        Map<String, String> values = Map.of(
                "merchantName", "StreamFlix",
                "oldAmount", "15.99",
                "newAmount", "17.99",
                "cardDisplay", "Debit ••9034",
                "phase", "preview",
                "effect", "A bill is due.");
        for (ActivityType type : ActivityType.values()) {
            String text = MessageTemplates.render(type, "Payday sweep", values);
            assertThat(text).isNotBlank();
            assertThat(text).doesNotContain("{");
            assertThat(text).doesNotContain("}");
        }
    }

    @Test
    void approvalRequestedUsesThePriceIncreaseText() {
        assertThat(MessageTemplates.render(
                        ActivityType.APPROVAL_REQUESTED,
                        "Subscription guard",
                        Map.of(
                                "merchantName", "StreamFlix",
                                "oldAmount", "15.99",
                                "newAmount", "17.99",
                                "cardDisplay", "Debit ••9034")))
                .isEqualTo(
                        "StreamFlix went from $15.99 to $17.99. Block future StreamFlix charges on Debit ••9034?");
    }

    @Test
    void fixedTextMatchesTheCustomerTemplates() {
        assertThat(MessageTemplates.render(ActivityType.CREATED, "Payday sweep")).isEqualTo("Payday sweep is on.");
        assertThat(MessageTemplates.render(ActivityType.PAUSED, "Payday sweep")).isEqualTo("Payday sweep is paused.");
        assertThat(MessageTemplates.render(ActivityType.RESUMED, "Payday sweep")).isEqualTo("Payday sweep is on again.");
        assertThat(MessageTemplates.render(ActivityType.DELETED, "Payday sweep")).isEqualTo("Payday sweep was deleted.");
        assertThat(MessageTemplates.render(ActivityType.FIRED, "Payday sweep")).isEqualTo("Payday sweep started a check.");
        assertThat(MessageTemplates.render(ActivityType.SKIPPED, "Payday sweep")).isEqualTo("Payday sweep did not run.");
        assertThat(MessageTemplates.render(ActivityType.APPROVAL_REQUESTED, "Payday sweep")).isEqualTo("Approve Payday sweep?");
        assertThat(MessageTemplates.render(ActivityType.APPROVED, "Payday sweep")).isEqualTo("Payday sweep was approved.");
        assertThat(MessageTemplates.render(ActivityType.DENIED, "Payday sweep")).isEqualTo("Payday sweep was declined.");
        assertThat(MessageTemplates.render(ActivityType.EXPIRED, "Payday sweep"))
                .isEqualTo("This request expired, nothing was changed.");
        assertThat(MessageTemplates.render(ActivityType.COMPLETED, "Payday sweep")).isEqualTo("Payday sweep is done.");
        assertThat(MessageTemplates.render(ActivityType.FAILED, "Payday sweep", Map.of("phase", "execute")))
                .isEqualTo("We couldn't complete this.");
        assertThat(MessageTemplates.render(ActivityType.FAILED, "Payday sweep", Map.of("phase", "preview")))
                .isEqualTo("We couldn't complete this. Nothing was changed.");
        assertThat(MessageTemplates.render(ActivityType.NOTIFIED, "Payday sweep", Map.of("effect", "A bill is due.")))
                .isEqualTo("A bill is due.");
        assertThat(MessageTemplates.render(ActivityType.CREATED, " ")).isEqualTo("This agent is on.");
    }
}
