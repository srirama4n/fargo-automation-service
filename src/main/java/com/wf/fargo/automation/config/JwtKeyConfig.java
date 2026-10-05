package com.wf.fargo.automation.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.stereotype.Component;

@Component
public class JwtKeyConfig {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public JwtKeyConfig() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            this.privateKey = (RSAPrivateKey) pair.getPrivate();
            this.publicKey = (RSAPublicKey) pair.getPublic();
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Could not generate the caller signing key", ex);
        }
    }

    RSAPrivateKey privateKey() {
        return privateKey;
    }

    RSAPublicKey publicKey() {
        return publicKey;
    }
}
