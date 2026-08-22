package com.shortener.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    @Test
    void acceptsOnlyAbsoluteHttpUrlsWithoutCredentials() {
        assertTrue(validator.isValid("https://example.com/path?q=value"));
        assertTrue(validator.isValid("http://sub.example.com"));
        assertFalse(validator.isValid("ftp://example.com"));
        assertFalse(validator.isValid("https://user:password@example.com"));
        assertFalse(validator.isValid("not-a-url"));
        assertFalse(validator.isValid(null));
    }

    @Test
    void classifiesDeviceFromUserAgent() {
        assertEquals("Mobile", validator.extractDeviceType("Mozilla/5.0 (iPhone)"));
        assertEquals("Tablet", validator.extractDeviceType("Mozilla/5.0 (iPad)"));
        assertEquals("Desktop", validator.extractDeviceType("Mozilla/5.0 (Windows NT 10.0)"));
        assertEquals("Unknown", validator.extractDeviceType(null));
    }
}
