package com.shortener.service;

import com.shortener.dto.ShortenUrlRequest;
import com.shortener.exception.InvalidUrlException;
import com.shortener.exception.ShortCodeAlreadyExistsException;
import com.shortener.model.UrlMapping;
import com.shortener.repository.UrlMappingRepository;
import com.shortener.util.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

class UrlShorteningServiceTest {

    private UrlMappingRepository repository;
    private ShortCodeGenerator generator;
    private CacheService cacheService;
    private AfterCommitExecutor afterCommitExecutor;
    private UrlShorteningService service;

    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingRepository.class);
        generator = mock(ShortCodeGenerator.class);
        cacheService = mock(CacheService.class);
        afterCommitExecutor = mock(AfterCommitExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(afterCommitExecutor).execute(any(Runnable.class));
        when(repository.save(any(UrlMapping.class))).thenAnswer(invocation -> {
            UrlMapping mapping = invocation.getArgument(0);
            if (mapping.getId() == null) {
                mapping.setId(1L);
            }
            return mapping;
        });
        service = new UrlShorteningService(
                repository, generator, new UrlValidator(), cacheService, afterCommitExecutor);
    }

    @Test
    void createsMappingAndInitialStats() {
        when(generator.generateUniqueShortCode()).thenReturn("abc123");

        UrlMapping mapping = service.createShortenedUrl(
                new ShortenUrlRequest("https://example.com/a/long/path", null));

        assertEquals("abc123", mapping.getShortCode());
        assertEquals("https://example.com/a/long/path", mapping.getOriginalUrl());
        assertTrue(mapping.isActive());
        assertNotNull(mapping.getStats());
        assertEquals(0L, mapping.getStats().getClickCount());
        verify(cacheService).putUrl(new ResolvedUrl(
                1L, "abc123", "https://example.com/a/long/path"));
    }

    @Test
    void rejectsInvalidUrl() {
        assertThrows(
                InvalidUrlException.class,
                () -> service.createShortenedUrl(new ShortenUrlRequest("not-a-url", null))
        );
    }

    @Test
    void rejectsDuplicateCustomCode() {
        when(repository.existsByShortCode("custom1")).thenReturn(true);

        assertThrows(
                ShortCodeAlreadyExistsException.class,
                () -> service.createShortenedUrl(
                        new ShortenUrlRequest("https://example.com/valid", "custom1"))
        );
    }

    @Test
    void returnsAnActiveMapping() {
        UrlMapping mapping = UrlMapping.builder().shortCode("abc123").active(true).build();
        when(repository.findByShortCodeAndActiveTrue("abc123")).thenReturn(Optional.of(mapping));

        assertEquals(mapping, service.getActiveMapping("abc123"));
    }

    @Test
    void deactivatesAnExistingMapping() {
        UrlMapping mapping = UrlMapping.builder().shortCode("abc123").active(true).build();
        when(repository.findByShortCodeAndActiveTrue("abc123")).thenReturn(Optional.of(mapping));

        service.deactivate("abc123");

        assertTrue(!mapping.isActive());
        verify(repository).save(mapping);
        verify(cacheService).invalidate("abc123");
    }

    @Test
    void resolvesFromCacheWithoutDatabaseLookup() {
        ResolvedUrl cached = new ResolvedUrl(1L, "abc123", "https://example.com");
        when(cacheService.getUrl("abc123")).thenReturn(Optional.of(cached));

        assertEquals(cached, service.resolveActiveUrl("abc123"));

        verify(repository, never()).findByShortCodeAndActiveTrue("abc123");
    }

    @Test
    void populatesCacheAfterDatabaseFallback() {
        UrlMapping mapping = UrlMapping.builder()
                .id(1L)
                .shortCode("abc123")
                .originalUrl("https://example.com")
                .active(true)
                .build();
        when(cacheService.getUrl("abc123")).thenReturn(Optional.empty());
        when(repository.findByShortCodeAndActiveTrue("abc123")).thenReturn(Optional.of(mapping));

        ResolvedUrl result = service.resolveActiveUrl("abc123");

        assertEquals("https://example.com", result.originalUrl());
        verify(cacheService).putUrl(result);
    }
}
