package com.shortener.repository;

import com.shortener.model.UrlAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UrlAnalyticsRepository extends JpaRepository<UrlAnalytics, Long> {

    List<UrlAnalytics> findByUrlMappingId(Long urlMappingId);

    @Query("select coalesce(a.deviceType, 'Unknown'), count(a) from UrlAnalytics a "
            + "where a.urlMapping.id = :urlId group by a.deviceType")
    List<Object[]> countByDeviceType(@Param("urlId") Long urlId);

    @Query("select coalesce(a.referrer, 'Direct'), count(a) from UrlAnalytics a "
            + "where a.urlMapping.id = :urlId group by a.referrer")
    List<Object[]> countByReferrer(@Param("urlId") Long urlId);

    @Query("select coalesce(a.country, 'Unknown'), count(a) from UrlAnalytics a "
            + "where a.urlMapping.id = :urlId group by a.country")
    List<Object[]> countByCountry(@Param("urlId") Long urlId);
}
