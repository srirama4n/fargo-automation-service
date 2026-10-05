package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.Approval;
import com.wf.fargo.automation.domain.ApprovalStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class ApprovalStore {

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public ApprovalStore(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    public Optional<Approval> decide(
            String approvalId, ApprovalStatus expected, ApprovalStatus next, Consumer<Update> extra) {
        Instant now = clock.instant();
        Update update = new Update().set("status", next).set("updatedAt", now);
        if (extra != null) {
            extra.accept(update);
        }
        Approval updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(approvalId).and("status").is(expected)),
                update,
                FindAndModifyOptions.options().returnNew(true),
                Approval.class);
        return Optional.ofNullable(updated);
    }
}
