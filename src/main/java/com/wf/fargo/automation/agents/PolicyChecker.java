package com.wf.fargo.automation.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.wf.fargo.automation.common.ApiError;
import com.wf.fargo.automation.common.CanonicalJson;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.AgentStatus;
import com.wf.fargo.automation.domain.AgentTemplate;
import com.wf.fargo.automation.domain.PersonalAgent;
import com.wf.fargo.automation.observe.AutomationMetrics;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class PolicyChecker {

    private static final Logger log = LoggerFactory.getLogger(PolicyChecker.class);

    private final MongoTemplate mongoTemplate;
    private final TemplateService templates;
    private final CustomerAccountsClient accounts;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private AutomationMetrics metrics;

    public PolicyChecker(
            MongoTemplate mongoTemplate,
            TemplateService templates,
            CustomerAccountsClient accounts,
            AppProperties properties,
            ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.templates = templates;
        this.accounts = accounts;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void setMetrics(AutomationMetrics metrics) {
        this.metrics = metrics;
    }

    public PolicyCheck check(String customerId, String templateId, int templateVersion, Map<String, Object> params) {
        Map<String, Object> body = params == null ? Map.of() : params;
        List<ApiError> errors = new ArrayList<>();
        Optional<AgentTemplate> found = templates.find(templateId, templateVersion);
        if (found.isEmpty()) {
            errors.add(new ApiError("templateId", "template_not_found", "That template is not available."));
        } else if (!"approved".equals(found.get().status())) {
            errors.add(new ApiError("templateId", "template_not_approved", "That template is not available."));
        }
        AgentTemplate template = found.filter(candidate -> "approved".equals(candidate.status())).orElse(null);
        if (template != null && template.paramSchema() != null) {
            errors.addAll(schemaErrors(template, body));
        }
        BigDecimal maxAmount = template == null || template.limits() == null ? null : template.limits().maxAmount();
        amounts(body, "params", maxAmount, errors);
        for (String token : ParamTokens.collect(body)) {
            if (!accounts.owns(customerId, token)) {
                errors.add(new ApiError("params", "account_not_owned", "That account isn't on this profile."));
                break;
            }
        }
        if (activeCount(customerId) >= properties.limits().maxAgentsPerCustomer()) {
            errors.add(new ApiError(
                    "customerId",
                    "agent_limit_reached",
                    "You can have up to " + properties.limits().maxAgentsPerCustomer() + " agents."));
        }
        if (templateId != null && duplicate(customerId, templateId, body)) {
            errors.add(new ApiError("params", "duplicate_agent", "You already have this agent."));
        }
        String effectText = template == null ? null : template.effectText();
        if (metrics != null) {
            for (ApiError error : errors) {
                metrics.policyRejected(error.code());
            }
        }
        return new PolicyCheck(List.copyOf(errors), Drafts.hash(objectMapper, templateId, templateVersion, body), effectText);
    }

    private List<ApiError> schemaErrors(AgentTemplate template, Map<String, Object> params) {
        try {
            JsonSchema schema = schemas.getSchema(objectMapper.valueToTree(plain(template.paramSchema())));
            JsonNode input = objectMapper.valueToTree(plain(params));
            List<ApiError> errors = new ArrayList<>();
            for (ValidationMessage message : schema.validate(input)) {
                errors.add(new ApiError(field(message), "invalid_param", "Check this value and try again."));
            }
            return errors;
        } catch (RuntimeException ex) {
            log.warn("policy_schema_invalid templateId={} error={}", template.templateId(), ex.getClass().getSimpleName());
            return List.of(new ApiError("params", "invalid_param", "Check this value and try again."));
        }
    }

    private static String field(ValidationMessage message) {
        String location = message.getInstanceLocation() == null ? "" : message.getInstanceLocation().toString();
        String path = location.replace("$.", "").replace("$", "").replace("/", ".").replaceAll("^\\.", "");
        if (path.isBlank()) {
            return "params";
        }
        return "params." + path;
    }

    private void amounts(Object value, String path, BigDecimal maxAmount, List<ApiError> errors) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String child = path + "." + key;
                if (key.toLowerCase(Locale.ROOT).contains("amount")) {
                    BigDecimal number = decimal(entry.getValue());
                    if (number != null && number.signum() <= 0) {
                        errors.add(new ApiError(child, "not_positive", "Amount must be greater than zero."));
                    } else if (number != null && maxAmount != null && number.compareTo(maxAmount) > 0) {
                        errors.add(new ApiError(
                                child,
                                "over_limit",
                                "The most this agent can move is " + money(maxAmount) + "."));
                    }
                }
                if ("currency".equalsIgnoreCase(key)
                        && entry.getValue() instanceof String currency
                        && !"USD".equals(currency)) {
                    errors.add(new ApiError(child, "unsupported_currency", "Amounts have to be in USD."));
                }
                amounts(entry.getValue(), child, maxAmount, errors);
            }
        } else if (value instanceof Iterable<?> iterable && !(value instanceof String)) {
            int index = 0;
            for (Object item : iterable) {
                amounts(item, path + "[" + index + "]", maxAmount, errors);
                index++;
            }
        }
    }

    private long activeCount(String customerId) {
        return mongoTemplate.count(activeQuery(customerId), PersonalAgent.class);
    }

    private boolean duplicate(String customerId, String templateId, Map<String, Object> params) {
        String canonical = canonical(params);
        Query query = activeQuery(customerId).addCriteria(Criteria.where("templateId").is(templateId));
        for (PersonalAgent agent : mongoTemplate.find(query, PersonalAgent.class)) {
            if (canonical.equals(canonical(agent.params()))) {
                return true;
            }
        }
        return false;
    }

    private String canonical(Map<String, Object> params) {
        return CanonicalJson.of(objectMapper.valueToTree(plain(params == null ? Map.of() : params)));
    }

    private static Object plain(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), plain(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof Iterable<?> iterable && !(value instanceof String)) {
            List<Object> copy = new ArrayList<>();
            for (Object item : iterable) {
                copy.add(plain(item));
            }
            return copy;
        }
        return value;
    }

    private static Query activeQuery(String customerId) {
        return Query.query(Criteria.where("customerId")
                .is(customerId)
                .and("status")
                .in(AgentStatus.ON, AgentStatus.PAUSED));
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return null;
    }

    private static String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
        int scale = Math.max(0, amount.stripTrailingZeros().scale());
        format.setMaximumFractionDigits(scale);
        format.setMinimumFractionDigits(0);
        return format.format(amount);
    }

    public record PolicyCheck(List<ApiError> errors, String draftHash, String effectText) {}
}
