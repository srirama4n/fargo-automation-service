package com.wf.fargo.automation.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wf.fargo.automation.EmbeddedMongoTest;
import com.wf.fargo.automation.domain.ActivityType;
import com.wf.fargo.automation.domain.AgentActivity;
import com.wf.fargo.automation.store.TransactionRunner;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class ActivityFeedTest extends EmbeddedMongoTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ActivityWriter activities;

    @Autowired
    private TransactionRunner transactions;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = capture(ActivityFeed.class.getName());
        Instant newest = Instant.parse("2026-10-03T00:00:00Z");
        Instant middle = Instant.parse("2026-10-02T00:00:00Z");
        Instant oldest = Instant.parse("2026-10-01T00:00:00Z");
        insert("evt_new", "cust_1", "agt_1", "run_1", ActivityType.FIRED, "Payday sweep started a check.", newest);
        insert("evt_mid", "cust_1", "agt_1", null, ActivityType.PAUSED, "Payday sweep is paused.", middle);
        insert("evt_old", "cust_1", "agt_2", "run_2", ActivityType.CREATED, "Payday sweep is on.", oldest);
        insert("evt_other", "cust_9", "agt_9", null, ActivityType.CREATED, "Other is on.", newest);
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(ActivityFeed.class)).detachAppender(logs);
    }

    @Test
    void feedPagesNewestFirstAndHidesAnotherCustomer() throws Exception {
        MvcResult first = mvc.perform(get("/v1/customers/cust_1/activity")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].eventId").value("evt_new"))
                .andExpect(jsonPath("$.items[0].type").value("fired"))
                .andExpect(jsonPath("$.items[0].message").value("Payday sweep started a check."))
                .andExpect(jsonPath("$.items[0].agentId").value("agt_1"))
                .andExpect(jsonPath("$.items[0].runId").value("run_1"))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[1].eventId").value("evt_mid"))
                .andExpect(jsonPath("$.items[1].type").value("paused"))
                .andExpect(jsonPath("$.items[1].runId").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");

        mvc.perform(get("/v1/customers/cust_1/activity")
                        .header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1")
                        .param("before", cursor)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value("evt_old"))
                .andExpect(jsonPath("$.items[0].type").value("created"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void anotherCustomerIsNotFound() throws Exception {
        mvc.perform(get("/v1/customers/cust_1/activity").header(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("security=true")
                && event.getFormattedMessage().contains("ownership_mismatch"));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("cust_1")
                || event.getFormattedMessage().contains("cust_9"));
    }

    @Test
    void writerRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> activities.write(
                        "cust_1", "agt_1", null, ActivityType.PAUSED, "Payday sweep is paused.", Map.of()))
                .isInstanceOf(IllegalStateException.class);
        transactions.inTransaction(() -> {
            activities.write("cust_1", "agt_1", null, ActivityType.RESUMED, "Payday sweep is on again.", Map.of());
            return null;
        });
        assertThat(mongoTemplate().find(Query.query(Criteria.where("type").is(ActivityType.RESUMED)), AgentActivity.class))
                .hasSize(1);
    }

    private void insert(
            String eventId,
            String customerId,
            String agentId,
            String runId,
            ActivityType type,
            String message,
            Instant createdAt) {
        mongoTemplate().insert(new AgentActivity(
                eventId, customerId, agentId, runId, type, message, Map.of(), createdAt, createdAt, 1));
    }

    private static ListAppender<ILoggingEvent> capture(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
