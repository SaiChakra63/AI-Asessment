package com.shortener.service;

import com.shortener.dto.UrlStatsResponse;
import com.shortener.model.UrlAnalytics;
import com.shortener.model.UrlMapping;
import com.shortener.model.UrlStats;
import com.shortener.repository.UrlAnalyticsRepository;
import com.shortener.repository.UrlMappingRepository;
import com.shortener.repository.UrlStatsRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UrlAnalyticsService {

    private final UrlStatsRepository urlStatsRepository;
    private final UrlAnalyticsRepository urlAnalyticsRepository;
    private final UrlMappingRepository urlMappingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final CacheService cacheService;
    private final AfterCommitExecutor afterCommitExecutor;

    public UrlAnalyticsService(
            UrlStatsRepository urlStatsRepository,
            UrlAnalyticsRepository urlAnalyticsRepository,
            UrlMappingRepository urlMappingRepository,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            CacheService cacheService,
            AfterCommitExecutor afterCommitExecutor
    ) {
        this.urlStatsRepository = urlStatsRepository;
        this.urlAnalyticsRepository = urlAnalyticsRepository;
        this.urlMappingRepository = urlMappingRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.cacheService = cacheService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Transactional
    public void recordAccess(
            ResolvedUrl resolvedUrl,
            RequestMetadata metadata
    ) {
        int insertedVisitor = jdbcTemplate.update(
                "INSERT INTO url_unique_visitors(url_id, visitor_hash) VALUES (?, ?) "
                        + "ON CONFLICT DO NOTHING",
                resolvedUrl.mappingId(),
                metadata.visitorHash()
        );
        long mobileDelta = "Mobile".equals(metadata.deviceType()) ? 1 : 0;
        long desktopDelta = "Desktop".equals(metadata.deviceType()) ? 1 : 0;
        long tabletDelta = "Tablet".equals(metadata.deviceType()) ? 1 : 0;
        int updated = urlStatsRepository.recordAccess(
                resolvedUrl.mappingId(),
                java.time.LocalDateTime.now(),
                insertedVisitor,
                mobileDelta,
                desktopDelta,
                tabletDelta
        );
        if (updated != 1) {
            throw new IllegalStateException("URL statistics row is missing");
        }

        urlAnalyticsRepository.save(UrlAnalytics.builder()
                .urlMapping(entityManager.getReference(UrlMapping.class, resolvedUrl.mappingId()))
                .deviceType(metadata.deviceType())
                .referrer(metadata.referrer())
                .ipAddress(metadata.clientIp())
                .userAgent(metadata.userAgent())
                .city(metadata.city())
                .country(metadata.country())
                .continent(metadata.continent())
                .visitorHash(metadata.visitorHash())
                .build());
        afterCommitExecutor.execute(() -> cacheService.invalidateStats(resolvedUrl.shortCode()));
    }

    @Transactional(readOnly = true)
    public UrlStatsResponse getStats(String shortCode) {
        var cached = cacheService.getStats(shortCode);
        if (cached.isPresent()) {
            return cached.get();
        }
        UrlMapping mapping = urlMappingRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new com.shortener.exception.UrlNotFoundException(
                        "No active URL found for short code: " + shortCode));
        UrlStats stats = urlStatsRepository.findByUrlMappingId(mapping.getId())
                .orElseThrow(() -> new IllegalStateException("URL statistics row is missing"));
        UrlStatsResponse response = new UrlStatsResponse(
                mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt(),
                stats.getClickCount(),
                stats.getUniqueVisitors(),
                stats.getLastAccessed(),
                toBreakdown(urlAnalyticsRepository.countByDeviceType(mapping.getId())),
                toBreakdown(urlAnalyticsRepository.countByReferrer(mapping.getId())),
                toBreakdown(urlAnalyticsRepository.countByCountry(mapping.getId()))
        );
        cacheService.putStats(shortCode, response);
        return response;
    }

    private Map<String, Long> toBreakdown(List<Object[]> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return result;
    }

}
