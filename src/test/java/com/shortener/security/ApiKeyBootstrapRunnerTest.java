package com.shortener.security;

import com.shortener.model.ApiClient;
import com.shortener.repository.ApiClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyBootstrapRunnerTest {

    private static final String RAW_KEY = "bootstrap-test-api-key-0123456789";
    private static final String TEST_PEPPER =
            "test-api-key-pepper-0123456789abcdef";

    @Test
    void normalRestartDoesNotReactivateOrOverwriteExistingClient() {
        ApiClientRepository repository = mock(ApiClientRepository.class);
        ApiClient existing = ApiClient.builder()
                .clientId("client-a")
                .displayName("Revoked client")
                .apiKeyDigest("old-digest")
                .authorities("URL_WRITE")
                .active(false)
                .build();
        when(repository.findById("client-a")).thenReturn(Optional.of(existing));
        ApiKeyBootstrapRunner runner = runner(repository, false);

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertFalse(existing.isActive());
        assertEquals("old-digest", existing.getApiKeyDigest());
        verify(repository, never()).save(any(ApiClient.class));
    }

    @Test
    void explicitUpdateRotatesAndReactivatesExistingClient() {
        ApiClientRepository repository = mock(ApiClientRepository.class);
        ApiClient existing = ApiClient.builder()
                .clientId("client-a")
                .displayName("Revoked client")
                .apiKeyDigest("old-digest")
                .authorities("URL_WRITE")
                .active(false)
                .build();
        when(repository.findById("client-a")).thenReturn(Optional.of(existing));
        ApiKeyBootstrapRunner runner = runner(repository, true);

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertEquals(new ApiKeyHasher(TEST_PEPPER).digest(RAW_KEY), existing.getApiKeyDigest());
        assertEquals("URL_WRITE,ANALYTICS_READ", existing.getAuthorities());
        verify(repository).save(existing);
    }

    @Test
    void rejectsThePublicExampleApiKeyValue() {
        ApiClientRepository repository = mock(ApiClientRepository.class);
        ApiKeyBootstrapRunner runner = new ApiKeyBootstrapRunner(
                repository,
                new ApiKeyHasher(TEST_PEPPER),
                "client-a",
                "Client A",
                "replace-with-a-random-client-api-key",
                "URL_WRITE",
                false
        );

        assertThrows(
                IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments(new String[0]))
        );
        verify(repository, never()).save(any(ApiClient.class));
    }

    private ApiKeyBootstrapRunner runner(ApiClientRepository repository, boolean updateExisting) {
        return new ApiKeyBootstrapRunner(
                repository,
                new ApiKeyHasher(TEST_PEPPER),
                "client-a",
                "Client A",
                RAW_KEY,
                "URL_WRITE,ANALYTICS_READ",
                updateExisting
        );
    }
}
