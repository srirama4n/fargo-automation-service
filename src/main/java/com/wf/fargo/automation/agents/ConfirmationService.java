package com.wf.fargo.automation.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.Ulids;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.Confirmation;
import com.wf.fargo.automation.observe.AutomationMetrics;
import com.wf.fargo.automation.store.ConfirmationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);
    private static final byte[] PROCESS_SECRET = new byte[32];
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    static {
        new SecureRandom().nextBytes(PROCESS_SECRET);
    }

    private final ConfirmationRepository confirmations;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final Clock clock;
    private final AutomationMetrics metrics;
    private final byte[] secret;

    public ConfirmationService(
            ConfirmationRepository confirmations,
            MongoTemplate mongoTemplate,
            ObjectMapper objectMapper,
            AppProperties properties,
            Clock clock,
            Environment environment,
            AutomationMetrics metrics) {
        this.confirmations = confirmations;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        String configured = environment.getProperty("AUTOMATION_CONFIRMATION_SECRET");
        this.secret = configured == null || configured.isBlank()
                ? PROCESS_SECRET
                : configured.getBytes(StandardCharsets.UTF_8);
    }

    public Issued issue(String customerId, String templateId, int templateVersion, Map<String, Object> params) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.limits().confirmationTtl());
        String confirmationId = "cnf_" + Ulids.next(clock.millis());
        String draftHash = Drafts.hash(objectMapper, templateId, templateVersion, params);
        confirmations.save(new Confirmation(
                confirmationId, customerId, draftHash, null, expiresAt, now, now, 1));
        return new Issued(sign(confirmationId, customerId, draftHash, expiresAt), expiresAt);
    }

    public Verified verify(String customerId, String token, String templateId, int templateVersion, Map<String, Object> params) {
        JsonNode claims = claims(token);
        if (claims == null) {
            throw invalid("malformed");
        }
        String confirmationId = text(claims, "jti");
        String tokenCustomer = text(claims, "customerId");
        String tokenHash = text(claims, "draftHash");
        long exp = claims.path("exp").asLong(0);
        String draftHash = Drafts.hash(objectMapper, templateId, templateVersion, params);
        Instant expiresAt = Instant.ofEpochSecond(exp);
        Confirmation stored = confirmationId == null ? null : confirmations.findById(confirmationId).orElse(null);
        if (confirmationId == null || stored == null) {
            throw invalid("malformed");
        }
        if (stored.usedAt() != null) {
            throw invalid("reused");
        }
        if (!customerId.equals(tokenCustomer)
                || !customerId.equals(stored.customerId())
                || !draftHash.equals(tokenHash)
                || !draftHash.equals(stored.draftHash())) {
            throw invalid("mismatch");
        }
        if (!clock.instant().isBefore(expiresAt) || !clock.instant().isBefore(stored.expiresAt())) {
            throw invalid("expired");
        }
        return new Verified(confirmationId, draftHash);
    }

    public void consume(String confirmationId, String customerId, String draftHash) {
        Instant now = clock.instant();
        Confirmation updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id")
                        .is(confirmationId)
                        .and("customerId")
                        .is(customerId)
                        .and("draftHash")
                        .is(draftHash)
                        .and("usedAt")
                        .is(null)
                        .and("expiresAt")
                        .gt(now)),
                new Update().set("usedAt", now).set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true),
                Confirmation.class);
        if (updated == null) {
            throw invalid("reused");
        }
    }

    private JsonNode claims(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        byte[] expected = sign(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            return null;
        }
        try {
            return objectMapper.readTree(DECODER.decode(parts[1]));
        } catch (IllegalArgumentException | java.io.IOException ex) {
            return null;
        }
    }

    private String sign(String confirmationId, String customerId, String draftHash, Instant expiresAt) {
        try {
            String header = ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(Map.of(
                    "jti", confirmationId,
                    "customerId", customerId,
                    "draftHash", draftHash,
                    "exp", expiresAt.getEpochSecond())));
            String signingInput = header + "." + payload;
            return signingInput + "." + ENCODER.encodeToString(sign(signingInput));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not issue a confirmation token", ex);
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Could not sign a confirmation token", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private ApiException invalid(String reason) {
        metrics.confirmationRejected(reason);
        log.warn("security=true confirmation_invalid");
        return new ApiException(403, "confirmation_invalid", "Confirmation doesn't match this agent.");
    }

    public record Issued(String confirmationToken, Instant expiresAt) {}

    public record Verified(String confirmationId, String draftHash) {}
}
