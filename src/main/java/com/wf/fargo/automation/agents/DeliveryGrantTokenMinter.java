package com.wf.fargo.automation.agents;

import com.wf.fargo.automation.domain.Grant;
import com.wf.fargo.automation.outbox.GrantTokenMinter;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class DeliveryGrantTokenMinter implements GrantTokenMinter {

    private final GrantTokenSigner signer;
    private final MongoTemplate mongoTemplate;

    public DeliveryGrantTokenMinter(GrantTokenSigner signer, MongoTemplate mongoTemplate) {
        this.signer = signer;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public String mint(Map<String, Object> taskPayload) {
        String agentId = text(taskPayload, "agentId");
        String customerId = text(taskPayload, "customerId");
        String runId = text(taskPayload, "runId");
        String taskId = text(taskPayload, "taskId");
        String grantId = text(taskPayload, "grantId");
        if (agentId == null || customerId == null || runId == null || taskId == null) {
            throw new IllegalStateException("Grant token claims are incomplete");
        }
        if (grantId == null) {
            Grant grant = mongoTemplate.findOne(Query.query(Criteria.where("agentId").is(agentId)), Grant.class);
            if (grant == null || grant.grantId() == null) {
                throw new IllegalStateException("Grant token claims are incomplete");
            }
            grantId = grant.grantId();
        }
        return signer.sign(agentId, grantId, customerId, runId, taskId);
    }

    private static String text(Map<String, Object> payload, String field) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }
}
