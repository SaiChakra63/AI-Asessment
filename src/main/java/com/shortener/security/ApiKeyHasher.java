package com.shortener.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

@Component
public class ApiKeyHasher {

    private static final int MINIMUM_PEPPER_BYTES = 32;

    private final byte[] pepper;
    private final boolean placeholder;

    public ApiKeyHasher(@Value("${app.security.api-key.pepper:}") String pepper) {
        String normalizedPepper = pepper.trim();
        this.pepper = normalizedPepper.getBytes(StandardCharsets.UTF_8);
        this.placeholder = normalizedPepper.startsWith("replace-with-");
    }

    public boolean isConfigured() {
        return pepper.length >= MINIMUM_PEPPER_BYTES && !placeholder;
    }

    /**
     * Computes the deterministic HMAC used for database lookup. Only this
     * digest is persisted; the raw client API key is never stored.
     */
    public String digest(String rawApiKey) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "API key pepper must contain at least 32 bytes and must not be an example value");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(rawApiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash API key", exception);
        }
    }
}
