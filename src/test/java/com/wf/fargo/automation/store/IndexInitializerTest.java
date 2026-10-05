package com.wf.fargo.automation.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.EmbeddedMongoTest;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IndexInitializerTest extends EmbeddedMongoTest {

    private static final List<String> AUDIT_COLLECTIONS = List.of(
            "agent_runs", "approvals", "grants", "agent_activity");

    @Autowired
    private IndexInitializer indexInitializer;

    @Test
    void runningTwiceIsHarmlessAndAuditsHaveNoTtl() {
        indexInitializer.ensureIndexes();
        indexInitializer.ensureIndexes();

        assertIndex("agent_templates", new Document("templateId", 1).append("version", 1), true);
        assertIndex("personal_agents", new Document("customerId", 1).append("status", 1), false);
        assertIndex("personal_agents", new Document("schedule.nextFireAt", 1).append("status", 1), false);
        assertIndex("grants", new Document("agentId", 1), true);
        assertIndex("agent_runs", new Document("idempotencyKey", 1), true);
        assertIndex("agent_runs", new Document("state", 1).append("updatedAt", 1), false);
        assertIndex("agent_runs", new Document("customerId", 1).append("createdAt", -1), false);
        assertIndex("approvals", new Document("status", 1).append("expiresAt", 1), false);
        assertIndex("approvals", new Document("runId", 1), true);
        assertIndex("agent_activity", new Document("customerId", 1).append("createdAt", -1), false);
        assertIndex("outbox", new Document("status", 1).append("nextAttemptAt", 1), false);

        Document confirmationTtl = indexes("confirmations").stream()
                .filter(index -> index.containsKey("expireAfterSeconds"))
                .findFirst()
                .orElseThrow();
        assertThat(confirmationTtl.get("key", Document.class)).isEqualTo(new Document("expiresAt", 1));
        assertThat(((Number) confirmationTtl.get("expireAfterSeconds")).longValue()).isZero();

        for (String collection : AUDIT_COLLECTIONS) {
            assertThat(indexes(collection))
                    .as(collection)
                    .allSatisfy(index -> assertThat(index.containsKey("expireAfterSeconds")).isFalse());
        }
    }

    private void assertIndex(String collection, Document key, boolean unique) {
        List<Document> matches = indexes(collection).stream()
                .filter(index -> key.equals(index.get("key", Document.class)))
                .toList();
        assertThat(matches).as("%s %s", collection, key).hasSize(1);
        if (unique) {
            assertThat(matches.getFirst().getBoolean("unique")).isTrue();
        }
    }

    private List<Document> indexes(String collection) {
        return mongoTemplate().getCollection(collection).listIndexes().into(new ArrayList<>());
    }
}
