package com.wf.fargo.automation.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class GrantSigningKey {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;

    public GrantSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            this.privateKey = (RSAPrivateKey) pair.getPrivate();
            this.publicKey = (RSAPublicKey) pair.getPublic();
            this.keyId = keyId(publicKey.getModulus());
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Could not generate the grant signing key", ex);
        }
    }

    public String keyId() {
        return keyId;
    }

    public ObjectNode publicJwk() {
        ObjectNode jwk = JSON.createObjectNode();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", keyId);
        jwk.put("n", ENCODER.encodeToString(unsigned(publicKey.getModulus())));
        jwk.put("e", ENCODER.encodeToString(unsigned(publicKey.getPublicExponent())));
        return jwk;
    }

    RSAPrivateKey privateKey() {
        return privateKey;
    }

    private static String keyId(BigInteger modulus) throws java.security.GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(unsigned(modulus));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            hex.append(Character.forDigit((digest[i] >> 4) & 0xf, 16));
            hex.append(Character.forDigit(digest[i] & 0xf, 16));
        }
        return hex.toString();
    }

    static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }
}
