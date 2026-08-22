package com.shortener.service;

import com.shortener.exception.ShortCodeGenerationException;
import com.shortener.repository.UrlMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class ShortCodeGenerator {

    private static final char[] ALPHANUMERIC =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int MAX_ATTEMPTS = 10;

    private final UrlMappingRepository urlMappingRepository;
    private final SecureRandom secureRandom;
    private final int codeLength;

    @Autowired
    public ShortCodeGenerator(
            UrlMappingRepository urlMappingRepository,
            @Value("${app.short-code.length:6}") int codeLength
    ) {
        this(urlMappingRepository, new SecureRandom(), codeLength);
    }

    ShortCodeGenerator(
            UrlMappingRepository urlMappingRepository,
            SecureRandom secureRandom,
            int codeLength
    ) {
        this.urlMappingRepository = urlMappingRepository;
        this.secureRandom = secureRandom;
        this.codeLength = codeLength;
    }

    public String generateUniqueShortCode() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = generateCandidate();
            if (!urlMappingRepository.existsByShortCode(candidate)) {
                return candidate;
            }
        }
        throw new ShortCodeGenerationException(
                "Unable to allocate a unique short code after " + MAX_ATTEMPTS + " attempts");
    }

    private String generateCandidate() {
        StringBuilder result = new StringBuilder(codeLength);
        for (int index = 0; index < codeLength; index++) {
            result.append(ALPHANUMERIC[secureRandom.nextInt(ALPHANUMERIC.length)]);
        }
        return result.toString();
    }
}
