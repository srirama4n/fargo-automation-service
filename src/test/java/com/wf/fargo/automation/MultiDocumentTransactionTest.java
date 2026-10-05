package com.wf.fargo.automation;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MultiDocumentTransactionTest extends EmbeddedMongoTest {

    @Autowired
    private MongoClient mongoClient;

    @Test
    void commitsMultiDocumentTransaction() {
        String databaseName = mongoTemplate().getDb().getName();
        try (ClientSession session = mongoClient.startSession()) {
            session.startTransaction();
            MongoDatabase database = mongoClient.getDatabase(databaseName);
            database.getCollection("tx_left").insertOne(session, new Document("_id", "left").append("value", "one"));
            database.getCollection("tx_right").insertOne(session, new Document("_id", "right").append("value", "two"));
            session.commitTransaction();
        }

        assertThat(mongoTemplate().findById("left", Document.class, "tx_left"))
                .isNotNull()
                .containsEntry("value", "one");
        assertThat(mongoTemplate().findById("right", Document.class, "tx_right"))
                .isNotNull()
                .containsEntry("value", "two");
    }
}
