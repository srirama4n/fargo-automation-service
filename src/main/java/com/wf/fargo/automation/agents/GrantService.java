package com.wf.fargo.automation.agents;

import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.store.GrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class GrantService {

    private final GrantRepository grants;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public GrantService(GrantRepository grants, MongoTemplate mongoTemplate, Clock clock) {
        this.grants = grants;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    public Grant issue(
            String agentId,
            String customerId,
            List<String> allowedTools,
            List<String> accountScopes,
            Map<String, Object> limits) {
        requireTransaction();
        Instant now = clock.instant();
        return grants.save(new Grant(
                "grt_" + Ulids.next(clock.millis()),
                agentId,
                customerId,
                List.copyOf(allowedTools),
                List.copyOf(accountScopes),
                limits,
                now,
                null,
                null,
                null,
                now,
                now,
                1));
    }

    public void revoke(String agentId, String reason) {
        requireTransaction();
        Instant now = clock.instant();
        mongoTemplate.findAndModify(
                Query.query(Criteria.where("agentId").is(agentId).and("revokedAt").is(null)),
                new Update().set("revokedAt", now).set("revokedReason", reason).set("updatedAt", now),
                Grant.class);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("GrantService must run inside a transaction");
        }
    }
}
