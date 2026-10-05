package com.wf.fargo.automation.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GrantTokenSigner {

    static final String ISSUER = "fargo-automation";
    static final String AUDIENCE = "fargo-mcp";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final GrantSigningKey key;
    private final Clock clock;
    private final Duration ttl;

    public GrantTokenSigner(GrantSigningKey key, Clock clock, Duration ttl) {
        this.key = key;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Autowired
    public GrantTokenSigner(GrantSigningKey key, Clock clock, AppProperties properties) {
        this(key, clock, properties.limits().grantTokenTtl());
    }

    public String sign(String agentId, String grantId, String customerId, String runId, String taskId) {
        require(agentId, "agentId");
        require(grantId, "grantId");
        require(customerId, "customerId");
        require(runId, "runId");
        require(taskId, "taskId");
        Instant issuedAt = clock.instant();
        long iat = issuedAt.getEpochSecond();
        long exp = issuedAt.plus(ttl).getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", key.keyId());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", agentId);
        claims.put("grantId", grantId);
        claims.put("customerId", customerId);
        claims.put("runId", runId);
        claims.put("taskId", taskId);
        claims.put("iat", iat);
        claims.put("exp", exp);
        String signingInput = encode(header) + "." + encode(claims);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + ENCODER.encodeToString(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not sign the grant token", ex);
        }
    }

    private static String encode(Map<String, Object> value) {
        try {
            return ENCODER.encodeToString(JSON.writeValueAsBytes(value));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode the grant token", ex);
        }
    }

    private static void require(String value, String claim) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Grant token claim is missing: " + claim);
        }
    }
}
