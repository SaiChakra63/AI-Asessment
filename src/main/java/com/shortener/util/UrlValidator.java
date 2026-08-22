package com.shortener.util;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Component
public class UrlValidator {

    public boolean isValid(String candidate) {
        if (candidate == null || candidate.length() < 10 || candidate.length() > 2048) {
            return false;
        }

        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) {
                return false;
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            return ("http".equals(normalizedScheme) || "https".equals(normalizedScheme))
                    && uri.getUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    public String extractDeviceType(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown";
        }
        String normalized = userAgent.toLowerCase(Locale.ROOT);
        if (normalized.contains("ipad") || normalized.contains("tablet")) {
            return "Tablet";
        }
        if (normalized.contains("iphone") || normalized.contains("android")
                || normalized.contains("mobile")) {
            return "Mobile";
        }
        return "Desktop";
    }
}
