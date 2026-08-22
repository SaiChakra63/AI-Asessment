package com.shortener.service;

import com.shortener.dto.ShortenUrlRequest;
import com.shortener.exception.InvalidUrlException;
import com.shortener.exception.ShortCodeAlreadyExistsException;
import com.shortener.exception.UrlNotFoundException;
import com.shortener.model.UrlMapping;
import com.shortener.model.UrlStats;
import com.shortener.repository.UrlMappingRepository;
import com.shortener.util.UrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlShorteningService {

    private final UrlMappingRepository urlMappingRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final UrlValidator urlValidator;
    private final CacheService cacheService;
    private final AfterCommitExecutor afterCommitExecutor;

    public UrlShorteningService(
            UrlMappingRepository urlMappingRepository,
            ShortCodeGenerator shortCodeGenerator,
            UrlValidator urlValidator,
            CacheService cacheService,
            AfterCommitExecutor afterCommitExecutor
    ) {
        this.urlMappingRepository = urlMappingRepository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.urlValidator = urlValidator;
        this.cacheService = cacheService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Transactional
    public UrlMapping createShortenedUrl(ShortenUrlRequest request) {
        String originalUrl = request.originalUrl().trim();
        if (!urlValidator.isValid(originalUrl)) {
            throw new InvalidUrlException(
                    "URL must be an absolute HTTP or HTTPS URL without embedded credentials");
        }

        String shortCode = chooseShortCode(request.customCode());
        UrlMapping mapping = UrlMapping.builder()
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .active(true)
                .build();
        mapping.attachStats(UrlStats.builder().clickCount(0).build());
        UrlMapping saved = urlMappingRepository.save(mapping);
        ResolvedUrl resolvedUrl = toResolvedUrl(saved);
        afterCommitExecutor.execute(() -> cacheService.putUrl(resolvedUrl));
        return saved;
    }

    @Transactional(readOnly = true)
    public ResolvedUrl resolveActiveUrl(String shortCode) {
        return cacheService.getUrl(shortCode).orElseGet(() -> {
            UrlMapping mapping = getActiveMapping(shortCode);
            ResolvedUrl resolvedUrl = toResolvedUrl(mapping);
            cacheService.putUrl(resolvedUrl);
            return resolvedUrl;
        });
    }

    @Transactional(readOnly = true)
    public UrlMapping getActiveMapping(String shortCode) {
        return urlMappingRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException("No active URL found for short code: " + shortCode));
    }

    @Transactional
    public void deactivate(String shortCode) {
        UrlMapping mapping = getActiveMapping(shortCode);
        mapping.setActive(false);
        urlMappingRepository.save(mapping);
        afterCommitExecutor.execute(() -> cacheService.invalidate(shortCode));
    }

    private String chooseShortCode(String customCode) {
        if (customCode == null || customCode.isBlank()) {
            return shortCodeGenerator.generateUniqueShortCode();
        }
        if (urlMappingRepository.existsByShortCode(customCode)) {
            throw new ShortCodeAlreadyExistsException("Custom short code is already in use");
        }
        return customCode;
    }

    private ResolvedUrl toResolvedUrl(UrlMapping mapping) {
        return new ResolvedUrl(mapping.getId(), mapping.getShortCode(), mapping.getOriginalUrl());
    }
}
