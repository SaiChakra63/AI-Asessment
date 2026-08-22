package com.shortener.service;

import com.shortener.dto.UrlStatsResponse;
import com.shortener.model.UrlAnalytics;
import com.shortener.model.UrlMapping;
import com.shortener.model.UrlStats;
import com.shortener.repository.UrlAnalyticsRepository;
import com.shortener.repository.UrlMappingRepository;
import com.shortener.repository.UrlStatsRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcTemplate;

class UrlAnalyticsServiceTest {

    private UrlStatsRepository statsRepository;
    private UrlAnalyticsRepository analyticsRepository;
    private UrlMappingRepository mappingRepository;
    private JdbcTemplate jdbcTemplate;
    private EntityManager entityManager;
    private CacheService cacheService;
    private AfterCommitExecutor afterCommitExecutor;
    private UrlAnalyticsService service;

    @BeforeEach
    void setUp() {
        statsRepository = mock(UrlStatsRepository.class);
        analyticsRepository = mock(UrlAnalyticsRepository.class);
        mappingRepository = mock(UrlMappingRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        entityManager = mock(EntityManager.class);
        cacheService = mock(CacheService.class);
        afterCommitExecutor = mock(AfterCommitExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(afterCommitExecutor).execute(any(Runnable.class));
        service = new UrlAnalyticsService(
                statsRepository,
                analyticsRepository,
                mappingRepository,
                jdbcTemplate,
                entityManager,
                cacheService,
                afterCommitExecutor
        );
    }

    @Test
    void recordsAccessAtomicallyAndInvalidatesCachedStats() {
        UrlMapping mapping = mapping();
        when(jdbcTemplate.update(anyString(), anyLong(), anyString())).thenReturn(1);
        when(statsRepository.recordAccess(eq(1L), any(LocalDateTime.class), eq(1L), eq(1L), eq(0L), eq(0L)))
                .thenReturn(1);
        when(entityManager.getReference(UrlMapping.class, 1L)).thenReturn(mapping);
        when(analyticsRepository.save(any(UrlAnalytics.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordAccess(
                new ResolvedUrl(1L, "abc123", "https://example.com"),
                new RequestMetadata(
                        "127.0.0.1", "Mozilla/5.0 (iPhone)", "Direct", "Mobile",
                        "Unknown", "Unknown", "Unknown", "visitor-hash")
        );

        verify(statsRepository).recordAccess(
                eq(1L), any(LocalDateTime.class), eq(1L), eq(1L), eq(0L), eq(0L));
        verify(analyticsRepository).save(any(UrlAnalytics.class));
        verify(cacheService).invalidateStats("abc123");
    }

    @Test
    void buildsStatisticsBreakdowns() {
        UrlMapping mapping = mapping();
        LocalDateTime accessedAt = LocalDateTime.now();
        UrlStats stats = UrlStats.builder()
                .urlMapping(mapping)
                .clickCount(3)
                .lastAccessed(accessedAt)
                .build();
        when(statsRepository.findByUrlMappingId(1L)).thenReturn(Optional.of(stats));
        when(cacheService.getStats("abc123")).thenReturn(Optional.empty());
        when(mappingRepository.findByShortCodeAndActiveTrue("abc123")).thenReturn(Optional.of(mapping));
        when(analyticsRepository.countByDeviceType(1L))
                .thenReturn(List.<Object[]>of(new Object[]{"Mobile", 2L}, new Object[]{"Desktop", 1L}));
        when(analyticsRepository.countByReferrer(1L))
                .thenReturn(List.<Object[]>of(new Object[]{"Direct", 3L}));
        when(analyticsRepository.countByCountry(1L))
                .thenReturn(List.<Object[]>of(new Object[]{"Unknown", 3L}));

        UrlStatsResponse response = service.getStats("abc123");

        assertEquals("abc123", response.shortCode());
        assertEquals(3L, response.totalClicks());
        assertEquals(2L, response.deviceBreakdown().get("Mobile"));
        assertEquals(3L, response.referrerBreakdown().get("Direct"));
        assertEquals(3L, response.locationBreakdown().get("Unknown"));
        verify(cacheService).putStats("abc123", response);
    }

    @Test
    void failsClosedWhenStatisticsRowIsMissing() {
        when(jdbcTemplate.update(anyString(), anyLong(), anyString())).thenReturn(0);
        when(statsRepository.recordAccess(eq(1L), any(LocalDateTime.class), eq(0L), eq(0L), eq(0L), eq(0L)))
                .thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.recordAccess(
                        new ResolvedUrl(1L, "abc123", "https://example.com"),
                        new RequestMetadata(
                                "127.0.0.1", "agent", "Direct", "Unknown",
                                "Unknown", "Unknown", "Unknown", "visitor-hash")));
    }

    private UrlMapping mapping() {
        return UrlMapping.builder()
                .id(1L)
                .shortCode("abc123")
                .originalUrl("https://example.com")
                .createdAt(LocalDateTime.now())
                .active(true)
                .build();
    }
}
