package com.shortener.service;

import com.shortener.util.UrlValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class RequestMetadataExtractor {

    private final UrlValidator urlValidator;
    private final boolean trustForwardedHeaders;
    private final boolean trustGeoHeaders;
    private final byte[] visitorHashSalt;

    public RequestMetadataExtractor(
            UrlValidator urlValidator,
            @Value("${app.proxy.trust-forwarded-headers:false}") boolean trustForwardedHeaders,
            @Value("${app.analytics.trust-geo-headers:false}") boolean trustGeoHeaders,
            @Value("${app.analytics.visitor-hash-salt}") String visitorHashSalt
    ) {
        this.urlValidator = urlValidator;
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.trustGeoHeaders = trustGeoHeaders;
        this.visitorHashSalt = visitorHashSalt.getBytes(StandardCharsets.UTF_8);
    }

    public RequestMetadata extract(HttpServletRequest request) {
        String clientIp = clientIp(request);
        String userAgent = truncate(request.getHeader("User-Agent"), 2048);
        return new RequestMetadata(
                clientIp,
                userAgent,
                normalize(request.getHeader("Referer"), "Direct", 255),
                urlValidator.extractDeviceType(userAgent),
                geoValue(request, "X-Geo-City"),
                geoValue(request, "X-Geo-Country"),
                geoValue(request, "X-Geo-Continent"),
                visitorHash(clientIp, userAgent)
        );
    }

    public String clientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return truncate(forwardedFor.split(",", 2)[0].trim(), 45);
            }
        }
        return truncate(request.getRemoteAddr(), 45);
    }

    private String geoValue(HttpServletRequest request, String header) {
        if (!trustGeoHeaders) {
            return "Unknown";
        }
        return normalize(request.getHeader(header), "Unknown", 100)
                .replaceAll("[^\\p{L}\\p{N} ._'-]", "");
    }

    private String visitorHash(String clientIp, String userAgent) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(visitorHashSalt, "HmacSHA256"));
            byte[] digest = mac.doFinal((clientIp + "|" + String.valueOf(userAgent))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash visitor identifier", exception);
        }
    }

    private String normalize(String value, String fallback, int maximumLength) {
        return value == null || value.isBlank() ? fallback : truncate(value, maximumLength);
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }
}
