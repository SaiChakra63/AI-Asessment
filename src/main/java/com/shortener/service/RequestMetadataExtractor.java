package com.shortener.service;

import com.shortener.util.UrlValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts bounded analytics metadata and derives a pseudonymous visitor ID.
 * Forwarded and geo headers are honored only when the immediate peer is an
 * explicitly trusted proxy; rotating the visitor secret resets visitor identity.
 */
@Component
public class RequestMetadataExtractor {

    private static final int MINIMUM_VISITOR_SECRET_BYTES = 32;

    private final UrlValidator urlValidator;
    private final boolean trustForwardedHeaders;
    private final boolean trustGeoHeaders;
    private final Set<String> trustedProxyAddresses;
    private final byte[] visitorHashSalt;

    public RequestMetadataExtractor(
            UrlValidator urlValidator,
            @Value("${app.proxy.trust-forwarded-headers:false}") boolean trustForwardedHeaders,
            @Value("${app.proxy.trusted-addresses:}") String trustedProxyAddresses,
            @Value("${app.analytics.trust-geo-headers:false}") boolean trustGeoHeaders,
            @Value("${app.analytics.visitor-hash-salt}") String visitorHashSalt
    ) {
        String normalizedSecret = visitorHashSalt == null ? "" : visitorHashSalt.trim();
        byte[] secretBytes = normalizedSecret.getBytes(StandardCharsets.UTF_8);
        if (normalizedSecret.isBlank()
                || normalizedSecret.startsWith("replace-with-")
                || secretBytes.length < MINIMUM_VISITOR_SECRET_BYTES) {
            throw new IllegalStateException(
                    "VISITOR_HASH_SALT must contain at least 32 bytes and must not be an example value");
        }
        this.urlValidator = urlValidator;
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.trustGeoHeaders = trustGeoHeaders;
        this.trustedProxyAddresses = parseTrustedProxyAddresses(trustedProxyAddresses);
        if ((trustForwardedHeaders || trustGeoHeaders) && this.trustedProxyAddresses.isEmpty()) {
            throw new IllegalStateException(
                    "TRUSTED_PROXY_ADDRESSES is required when forwarded or geo headers are trusted");
        }
        this.visitorHashSalt = secretBytes;
    }

    public RequestMetadata extract(HttpServletRequest request) {
        String clientIp = clientIp(request);
        String userAgent = truncate(request.getHeader("User-Agent"), 2048);
        return new RequestMetadata(
                clientIp,
                userAgent,
                sanitizeReferrer(request.getHeader("Referer")),
                urlValidator.extractDeviceType(userAgent),
                geoValue(request, "X-Geo-City"),
                geoValue(request, "X-Geo-Country"),
                geoValue(request, "X-Geo-Continent"),
                visitorHash(clientIp, userAgent)
        );
    }

    public String clientIp(HttpServletRequest request) {
        if (trustForwardedHeaders && isTrustedProxy(request)) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                Optional<String> parsed = parseIpLiteral(forwardedFor.split(",", 2)[0].trim());
                if (parsed.isPresent()) {
                    return parsed.get();
                }
            }
        }
        return parseIpLiteral(request.getRemoteAddr()).orElse("unknown");
    }

    private String geoValue(HttpServletRequest request, String header) {
        if (!trustGeoHeaders || !isTrustedProxy(request)) {
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

    private String sanitizeReferrer(String value) {
        if (value == null || value.isBlank()) {
            return "Direct";
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return "Invalid";
            }
            String authority = uri.getPort() < 0 ? host : host + ":" + uri.getPort();
            return truncate(scheme.toLowerCase() + "://" + authority.toLowerCase(), 255);
        } catch (IllegalArgumentException exception) {
            return "Invalid";
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

    private boolean isTrustedProxy(HttpServletRequest request) {
        return parseIpLiteral(request.getRemoteAddr())
                .map(trustedProxyAddresses::contains)
                .orElse(false);
    }

    private Set<String> parseTrustedProxyAddresses(String configuredAddresses) {
        return Arrays.stream(configuredAddresses.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::parseRequiredIpLiteral)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String parseRequiredIpLiteral(String value) {
        return parseIpLiteral(value).orElseThrow(() -> new IllegalStateException(
                "TRUSTED_PROXY_ADDRESSES contains an invalid IP address"));
    }

    private Optional<String> parseIpLiteral(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9a-fA-F:.]+")) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(value).getHostAddress());
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }
}
