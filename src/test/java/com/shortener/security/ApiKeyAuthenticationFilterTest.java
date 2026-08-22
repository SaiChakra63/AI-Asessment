package com.shortener.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortener.repository.ApiClientRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthenticationFilterTest {

    @Test
    void returnsServiceUnavailableWhenAuthenticationStoreFails() throws Exception {
        ApiClientRepository repository = mock(ApiClientRepository.class);
        when(repository.findByApiKeyDigestAndActiveTrue(anyString()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
                repository,
                new ApiKeyHasher("test-api-key-pepper-0123456789abcdef"),
                new RestSecurityErrorWriter(objectMapper),
                "X-API-Key"
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/urls/shorten");
        request.addHeader("X-API-Key", "client-api-key-0123456789-abcdef");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains("AUTHENTICATION_SERVICE_UNAVAILABLE"));
        verify(chain, never()).doFilter(request, response);
    }
}
