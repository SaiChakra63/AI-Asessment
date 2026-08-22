package com.shortener.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiKeyHasherTest {

    private static final String TEST_PEPPER =
            "test-api-key-pepper-0123456789abcdef";

    @Test
    void producesStableHmacSha256Digest() {
        ApiKeyHasher hasher = new ApiKeyHasher(TEST_PEPPER);

        assertEquals(
                "66e722a9a7a4ba3a61646e3fdd73d0331b226e31cf89bab5231f8d500b790f64",
                hasher.digest("test-api-key")
        );
        assertNotEquals(hasher.digest("test-api-key"), hasher.digest("different-api-key"));
    }

    @Test
    void failsClosedWithoutPepper() {
        ApiKeyHasher hasher = new ApiKeyHasher("");

        assertThrows(IllegalStateException.class, () -> hasher.digest("test-api-key"));
    }

    @Test
    void failsClosedForShortPepper() {
        ApiKeyHasher hasher = new ApiKeyHasher("too-short");

        assertThrows(IllegalStateException.class, () -> hasher.digest("test-api-key"));
    }

    @Test
    void failsClosedForExamplePlaceholderPepper() {
        ApiKeyHasher hasher = new ApiKeyHasher("replace-with-a-random-server-side-pepper");

        assertThrows(IllegalStateException.class, () -> hasher.digest("test-api-key"));
    }
}
