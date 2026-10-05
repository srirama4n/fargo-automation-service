package com.wf.fargo.automation.agents;

import com.wf.fargo.automation.admin.AdminRequests.PublishTemplateRequest;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class TemplateService {

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public TemplateService(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    public List<AgentTemplate> list(String status) {
        String wanted = status == null || status.isBlank() ? "approved" : status;
        Query query = Query.query(Criteria.where("status").is(wanted)).with(Sort.by("name"));
        return mongoTemplate.find(query, AgentTemplate.class);
    }

    public Optional<AgentTemplate> find(String templateId, int version) {
        return Optional.ofNullable(mongoTemplate.findOne(
                Query.query(Criteria.where("templateId").is(templateId).and("version").is(version)),
                AgentTemplate.class));
    }

    public AgentTemplate publish(PublishTemplateRequest request, String adminId) {
        if (request.templateId() == null || request.templateId().isBlank()
                || request.version() == null
                || request.name() == null || request.name().isBlank()
                || request.trigger() == null || request.trigger().type() == null || request.trigger().type().isBlank()
                || request.action() == null || request.action().type() == null || request.action().type().isBlank()
                || request.action().previewIntent() == null || request.action().previewIntent().isBlank()
                || request.action().executeIntent() == null || request.action().executeIntent().isBlank()
                || request.mode() == null || request.mode().isBlank()
                || request.effectText() == null || request.effectText().isBlank()
                || request.requiredTools() == null) {
            throw new ApiException(400, "validation_error", "A complete template is required.");
        }
        if (find(request.templateId(), request.version()).isPresent()) {
            throw new ApiException(409, "version_conflict", "This template version already exists.");
        }
        Instant now = clock.instant();
        String reviewer = request.reviewedBy() == null || request.reviewedBy().isBlank()
                ? adminId
                : request.reviewedBy();
        AgentTemplate template = new AgentTemplate(
                request.templateId() + ":" + request.version(),
                request.templateId(),
                request.version(),
                request.name(),
                request.description(),
                request.trigger(),
                request.action(),
                mode(request.mode()),
                request.paramSchema() == null ? Map.of() : request.paramSchema(),
                request.limits(),
                List.copyOf(request.requiredTools()),
                request.approvalTtl(),
                request.effectText(),
                "approved",
                reviewer,
                now,
                now,
                now,
                1);
        try {
            mongoTemplate.insert(template);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(409, "version_conflict", "This template version already exists.");
        }
        return template;
    }

    private static AgentMode mode(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return AgentMode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, "validation_error", "mode must be ask, act_then_tell, or read.");
        }
    }
}
