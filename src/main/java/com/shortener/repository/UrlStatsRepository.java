package com.shortener.repository;

import com.shortener.model.UrlStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UrlStatsRepository extends JpaRepository<UrlStats, Long> {

    Optional<UrlStats> findByUrlMappingId(Long urlMappingId);

    @Modifying(clearAutomatically = true)
    @Query("update UrlStats s set s.clickCount = s.clickCount + 1, "
            + "s.lastAccessed = :accessedAt, "
            + "s.updatedAt = :accessedAt, "
            + "s.uniqueVisitors = s.uniqueVisitors + :uniqueVisitorDelta, "
            + "s.mobileClicks = s.mobileClicks + :mobileDelta, "
            + "s.desktopClicks = s.desktopClicks + :desktopDelta, "
            + "s.tabletClicks = s.tabletClicks + :tabletDelta "
            + "where s.urlMapping.id = :urlId")
    int recordAccess(
            @Param("urlId") Long urlId,
            @Param("accessedAt") LocalDateTime accessedAt,
            @Param("uniqueVisitorDelta") long uniqueVisitorDelta,
            @Param("mobileDelta") long mobileDelta,
            @Param("desktopDelta") long desktopDelta,
            @Param("tabletDelta") long tabletDelta
    );
}
