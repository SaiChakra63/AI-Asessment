package com.shortener.repository;

import com.shortener.model.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiClientRepository extends JpaRepository<ApiClient, String> {

    Optional<ApiClient> findByApiKeyDigestAndActiveTrue(String apiKeyDigest);
}
