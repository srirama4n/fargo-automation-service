package com.wf.fargo.automation.notify;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.domain.AgentActivity;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class ActivityFeed {

    private static final Logger log = LoggerFactory.getLogger(ActivityFeed.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final MongoTemplate mongoTemplate;

    public ActivityFeed(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public ActivityPage list(String callerId, String customerId, String before, Integer limit) {
        if (callerId == null || !callerId.equals(customerId)) {
            log.warn("security=true ownership_mismatch");
            throw new ApiException(404, "not_found", "Not found.");
        }
        int pageSize = pageSize(limit);
        Query query = Query.query(criteria(customerId, before))
                .with(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "_id")))
                .limit(pageSize + 1);
        List<AgentActivity> found = mongoTemplate.find(query, AgentActivity.class);
        boolean more = found.size() > pageSize;
        List<AgentActivity> page = more ? found.subList(0, pageSize) : found;
        String nextCursor = more ? cursor(page.get(pageSize - 1)) : null;
        return new ActivityPage(page.stream().map(ActivityFeed::item).toList(), nextCursor);
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(400, "validation_error", "limit must be from 1 to 100.");
        }
        return limit;
    }

    private static Criteria criteria(String customerId, String before) {
        Criteria owned = Criteria.where("customerId").is(customerId);
        if (before == null || before.isBlank()) {
            return owned;
        }
        int split = before.indexOf('|');
        if (split <= 0 || split == before.length() - 1) {
            throw new ApiException(400, "validation_error", "before is not a valid cursor.");
        }
        long millis;
        try {
            millis = Long.parseLong(before.substring(0, split));
        } catch (NumberFormatException exception) {
            throw new ApiException(400, "validation_error", "before is not a valid cursor.");
        }
        Instant createdAt = Instant.ofEpochMilli(millis);
        String eventId = before.substring(split + 1);
        return new Criteria().andOperator(
                owned,
                new Criteria().orOperator(
                        Criteria.where("createdAt").lt(createdAt),
                        new Criteria().andOperator(
                                Criteria.where("createdAt").is(createdAt), Criteria.where("_id").lt(eventId))));
    }

    private static String cursor(AgentActivity event) {
        return event.createdAt().toEpochMilli() + "|" + event.eventId();
    }

    private static ActivityItem item(AgentActivity event) {
        String type = event.type() == null ? null : event.type().name().toLowerCase(Locale.ROOT);
        return new ActivityItem(event.eventId(), type, event.message(), event.agentId(), event.runId(), event.createdAt());
    }

    public record ActivityItem(
            String eventId,
            String type,
            String message,
            String agentId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String runId,
            Instant createdAt) {}

    public record ActivityPage(
            List<ActivityItem> items, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {}
}
