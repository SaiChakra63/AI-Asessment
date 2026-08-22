package com.shortener.repository;

import com.shortener.model.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCodeAndActiveTrue(String shortCode);

    Optional<UrlMapping> findByShortCodeAndActiveTrueAndOwnerClientId(
            String shortCode,
            String ownerClientId
    );

    boolean existsByShortCode(String shortCode);
}
