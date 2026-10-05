package com.wf.fargo.automation.standalone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.domain.AgentMode;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.TemplateAction;
import com.wf.fargo.automation.domain.TemplateLimits;
import com.wf.fargo.automation.domain.TemplateTrigger;
import com.wf.fargo.automation.store.IndexInitializer;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
@Profile({"standalone", "test"})
@Order
public class TemplateSeeder implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final IndexInitializer indexes;

    public TemplateSeeder(MongoTemplate mongoTemplate, ObjectMapper objectMapper, IndexInitializer indexes) {
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        this.indexes = indexes;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        indexes.ensureIndexes();
        seed();
    }

    public void seed() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:standalone/templates/*.json");
        for (Resource resource : resources) {
            try (InputStream input = resource.getInputStream()) {
                TemplateFile file = objectMapper.readValue(input, TemplateFile.class);
                boolean exists = mongoTemplate.exists(
                        Query.query(Criteria.where("templateId").is(file.templateId()).and("version").is(file.version())),
                        AgentTemplate.class);
                if (exists) {
                    continue;
                }
                Instant reviewedAt = Instant.parse(file.reviewedAt());
                mongoTemplate.insert(new AgentTemplate(
                        file.templateId() + ":" + file.version(),
                        file.templateId(),
                        file.version(),
                        file.name(),
                        file.description(),
                        new TemplateTrigger(file.trigger().type()),
                        new TemplateAction(file.action().type(), file.action().previewIntent(), file.action().executeIntent()),
                        AgentMode.valueOf(file.mode()),
                        file.paramSchema() == null ? Map.of() : file.paramSchema(),
                        limits(file.limits()),
                        file.requiredTools() == null ? List.of() : List.copyOf(file.requiredTools()),
                        file.approvalTtl() == null ? null : Duration.parse(file.approvalTtl()),
                        file.effectText(),
                        file.status(),
                        file.reviewedBy(),
                        reviewedAt,
                        reviewedAt,
                        reviewedAt,
                        1));
            }
        }
    }

    private static TemplateLimits limits(LimitsFile limits) {
        if (limits == null) {
            return new TemplateLimits(null, null, null);
        }
        return new TemplateLimits(
                limits.maxAmount() == null ? null : new BigDecimal(limits.maxAmount()),
                limits.maxRunsPerDay(),
                limits.maxRunsPerPeriod());
    }

    public record TemplateFile(
            String templateId,
            int version,
            String name,
            String description,
            TriggerFile trigger,
            ActionFile action,
            String mode,
            Map<String, Object> paramSchema,
            LimitsFile limits,
            List<String> requiredTools,
            String approvalTtl,
            String effectText,
            String status,
            String reviewedBy,
            String reviewedAt) {}

    public record TriggerFile(String type) {}

    public record ActionFile(String type, String previewIntent, String executeIntent) {}

    public record LimitsFile(String maxAmount, Integer maxRunsPerDay, Integer maxRunsPerPeriod) {}
}
