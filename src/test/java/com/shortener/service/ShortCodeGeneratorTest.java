package com.shortener.service;

import com.shortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortCodeGeneratorTest {

    @Test
    void generatesSixCharacterAlphanumericCode() {
        UrlMappingRepository repository = mock(UrlMappingRepository.class);
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(62)).thenReturn(0, 1, 2, 3, 4, 5);
        when(repository.existsByShortCode(anyString())).thenReturn(false);

        String code = new ShortCodeGenerator(repository, random, 6).generateUniqueShortCode();

        assertEquals(6, code.length());
        assertTrue(code.matches("^[a-zA-Z0-9]+$"));
        assertEquals("abcdef", code);
    }

    @Test
    void retriesWhenCandidateCollides() {
        UrlMappingRepository repository = mock(UrlMappingRepository.class);
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(62)).thenReturn(0);
        when(repository.existsByShortCode("aaaaaa")).thenReturn(true, false);

        String code = new ShortCodeGenerator(repository, random, 6).generateUniqueShortCode();

        assertEquals("aaaaaa", code);
    }
}
