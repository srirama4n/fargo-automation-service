package com.wf.fargo.automation.store;

import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

import com.wf.fargo.automation.domain.AgentActivity;
import com.wf.fargo.automation.domain.AgentRun;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.Confirmation;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.domain.PersonalAgent;
import java.time.Duration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class IndexInitializer implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    public IndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndexes();
    }

    public void ensureIndexes() {
        ensure(AgentTemplate.class, new Index().on("templateId", ASC).on("version", ASC).unique());
        ensure(PersonalAgent.class, new Index().on("customerId", ASC).on("status", ASC));
        ensure(PersonalAgent.class, new Index().on("schedule.nextFireAt", ASC).on("status", ASC));
        ensure(Grant.class, new Index().on("agentId", ASC).unique());
        ensure(AgentRun.class, new Index().on("idempotencyKey", ASC).unique());
        ensure(AgentRun.class, new Index().on("state", ASC).on("updatedAt", ASC));
        ensure(AgentRun.class, new Index().on("customerId", ASC).on("createdAt", DESC));
        ensure(Approval.class, new Index().on("status", ASC).on("expiresAt", ASC));
        ensure(Approval.class, new Index().on("runId", ASC).unique());
        ensure(AgentActivity.class, new Index().on("customerId", ASC).on("createdAt", DESC));
        ensure(Confirmation.class, new Index().on("expiresAt", ASC).expire(Duration.ZERO));
        ensure(OutboxMessage.class, new Index().on("status", ASC).on("nextAttemptAt", ASC));
    }

    private void ensure(Class<?> type, Index index) {
        mongoTemplate.indexOps(type).ensureIndex(index);
    }
}
