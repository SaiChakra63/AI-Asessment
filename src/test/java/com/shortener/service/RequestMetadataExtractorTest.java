package com.shortener.service;

import com.shortener.util.UrlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestMetadataExtractorTest {

    private static final String VISITOR_SECRET =
            "test-visitor-hmac-secret-0123456789abcdef";

    @Test
    void ignoresForwardedHeadersFromAnUntrustedPeer() {
        RequestMetadataExtractor extractor = extractor(false, "", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.10");
        request.addHeader("X-Forwarded-For", "203.0.113.20");

        assertEquals("198.51.100.10", extractor.clientIp(request));
    }

    @Test
    void acceptsForwardedAndGeoHeadersOnlyFromAnAllowlistedProxy() {
        RequestMetadataExtractor extractor = extractor(true, "192.0.2.10", true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.20, 192.0.2.10");
        request.addHeader("X-Geo-Country", "India");

        RequestMetadata metadata = extractor.extract(request);

        assertEquals("203.0.113.20", metadata.clientIp());
        assertEquals("India", metadata.country());
    }

    @Test
    void removesPathQueryAndFragmentFromReferrer() {
        RequestMetadataExtractor extractor = extractor(false, "", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.10");
        request.addHeader("Referer", "https://search.example/private?q=token#result");

        assertEquals("https://search.example", extractor.extract(request).referrer());
    }

    @Test
    void rejectsUnsafeVisitorSecretsAndMissingProxyAllowlist() {
        assertThrows(IllegalStateException.class,
                () -> new RequestMetadataExtractor(new UrlValidator(), false, "", false, "short"));
        assertThrows(IllegalStateException.class,
                () -> new RequestMetadataExtractor(
                        new UrlValidator(), false, "", false,
                        "replace-with-a-long-random-secret-value"));
        assertThrows(IllegalStateException.class,
                () -> new RequestMetadataExtractor(
                        new UrlValidator(), true, "", false, VISITOR_SECRET));
    }

    private RequestMetadataExtractor extractor(
            boolean trustForwarded,
            String trustedAddresses,
            boolean trustGeo
    ) {
        return new RequestMetadataExtractor(
                new UrlValidator(), trustForwarded, trustedAddresses, trustGeo, VISITOR_SECRET);
    }
}
