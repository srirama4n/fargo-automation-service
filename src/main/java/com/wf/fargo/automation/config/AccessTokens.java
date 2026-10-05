package com.wf.fargo.automation.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AccessTokens {

    public static final String CUSTOMER_ISSUER = "fargo-gateway";
    public static final String CUSTOMER_AUDIENCE = "fargo-app";
    public static final String ADMIN_ISSUER = "fargo-sso";
    public static final String ADMIN_AUDIENCE = "fargo-automation";
    public static final String ADMIN_ROLE = "automation-admin";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final JwtKeyConfig key;
    private final Clock clock;

    public AccessTokens(JwtKeyConfig key, Clock clock) {
        this.key = key;
        this.clock = clock;
    }

    public String customerToken(String customerId) {
        return customerToken(customerId, CUSTOMER_ISSUER, CUSTOMER_AUDIENCE, null);
    }

    public String customerToken(String customerId, String issuer, String audience, Instant expiresAt) {
        Instant issuedAt = clock.instant();
        Instant expiry = expiresAt == null ? issuedAt.plus(DEFAULT_TTL) : expiresAt;
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("customerId", customerId);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiry.getEpochSecond());
        return sign(claims);
    }

    public String adminToken(String adminId) {
        return adminToken(adminId, ADMIN_ROLE, null);
    }

    public String adminToken(String adminId, String role, Instant expiresAt) {
        Instant issuedAt = clock.instant();
        Instant expiry = expiresAt == null ? issuedAt.plus(DEFAULT_TTL) : expiresAt;
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ADMIN_ISSUER);
        claims.put("aud", ADMIN_AUDIENCE);
        claims.put("sub", adminId);
        claims.put("role", role);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiry.getEpochSecond());
        return sign(claims);
    }

    public Identity verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized();
        }
        JsonNode header = read(decode(parts[0]));
        if (header == null || !"RS256".equals(header.path("alg").asText())) {
            throw unauthorized();
        }
        if (!verifySignature(parts[0] + "." + parts[1], decode(parts[2]))) {
            throw unauthorized();
        }
        JsonNode claims = read(decode(parts[1]));
        if (claims == null || claims.path("exp").asLong(0) <= clock.instant().getEpochSecond()) {
            throw unauthorized();
        }
        String issuer = claims.path("iss").asText("");
        String audience = claims.path("aud").asText("");
        if (ADMIN_ISSUER.equals(issuer) && ADMIN_AUDIENCE.equals(audience)) {
            String subject = claims.path("sub").asText("");
            if (!ADMIN_ROLE.equals(claims.path("role").asText()) || subject.isBlank()) {
                throw unauthorized();
            }
            return new Identity(null, subject);
        }
        if (CUSTOMER_ISSUER.equals(issuer) && CUSTOMER_AUDIENCE.equals(audience)) {
            String customerId = claims.path("customerId").asText("");
            if (customerId.isBlank()) {
                throw unauthorized();
            }
            return new Identity(customerId, null);
        }
        throw unauthorized();
    }

    private String sign(Map<String, Object> claims) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        String signingInput = encode(header) + "." + encode(claims);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + ENCODER.encodeToString(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not sign the access token", ex);
        }
    }

    private boolean verifySignature(String signingInput, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key.publicKey());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException ex) {
            return false;
        }
    }

    private static String encode(Map<String, Object> value) {
        try {
            return ENCODER.encodeToString(JSON.writeValueAsBytes(value));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not encode the access token", ex);
        }
    }

    private static byte[] decode(String part) {
        try {
            return DECODER.decode(part);
        } catch (IllegalArgumentException ex) {
            throw unauthorized();
        }
    }

    private static JsonNode read(byte[] bytes) {
        try {
            return JSON.readTree(bytes);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "unauthorized", "Sign in is required.");
    }

    public record Identity(String customerId, String adminId) {}
}
