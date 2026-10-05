package com.wf.fargo.automation.grants;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wf.fargo.automation.agents.GrantSigningKey;
import com.wf.fargo.automation.agents.GrantTokenSigner;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GrantTokenSignerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-04T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static GrantSigningKey key;
    private static GrantTokenSigner signer;

    @BeforeAll
    static void signer() {
        key = new GrantSigningKey();
        signer = new GrantTokenSigner(key, Clock.fixed(ISSUED_AT, ZoneOffset.UTC), TTL);
    }

    @Test
    void signSetsTheGrantClaimsAndFifteenMinuteExpiry() throws Exception {
        String token = signer.sign("agt_1", "grt_1", "cust_1", "run_1", "tsk_1");
        JsonNode header = part(token, 0);
        JsonNode claims = part(token, 1);

        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        assertThat(header.get("kid").asText()).isEqualTo(key.keyId());
        assertThat(claims.get("iss").asText()).isEqualTo("fargo-automation");
        assertThat(claims.get("aud").asText()).isEqualTo("fargo-mcp");
        assertThat(claims.get("sub").asText()).isEqualTo("agt_1");
        assertThat(claims.get("grantId").asText()).isEqualTo("grt_1");
        assertThat(claims.get("customerId").asText()).isEqualTo("cust_1");
        assertThat(claims.get("runId").asText()).isEqualTo("run_1");
        assertThat(claims.get("taskId").asText()).isEqualTo("tsk_1");
        assertThat(claims.get("iat").asLong()).isEqualTo(ISSUED_AT.getEpochSecond());
        assertThat(claims.get("exp").asLong()).isEqualTo(ISSUED_AT.plus(TTL).getEpochSecond());
        assertThat(verify(token, key.publicJwk(), ISSUED_AT.plus(TTL).minusSeconds(1))).isTrue();
    }

    @Test
    void expiredTokenFailsVerification() throws Exception {
        String token = signer.sign("agt_1", "grt_1", "cust_1", "run_1", "tsk_1");

        assertThat(verify(token, key.publicJwk(), ISSUED_AT.plus(TTL))).isFalse();
    }

    @Test
    void tamperedSignatureFailsVerification() throws Exception {
        String token = signer.sign("agt_1", "grt_1", "cust_1", "run_1", "tsk_1");
        String[] parts = token.split("\\.");
        String broken = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 2) + "aa";

        assertThat(verify(broken, key.publicJwk(), ISSUED_AT)).isFalse();
    }

    static boolean verify(String token, JsonNode jwk, Instant now) throws Exception {
        try {
            String[] parts = token.split("\\.");
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey(jwk));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signature)) {
                return false;
            }
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!"fargo-automation".equals(claims.get("iss").asText())
                    || !"fargo-mcp".equals(claims.get("aud").asText())) {
                return false;
            }
            return now.getEpochSecond() < claims.get("exp").asLong();
        } catch (IllegalArgumentException | java.security.GeneralSecurityException ex) {
            return false;
        }
    }

    private static java.security.PublicKey publicKey(JsonNode jwk) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").asText()));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").asText()));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private static JsonNode part(String token, int index) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[index]));
    }
}
