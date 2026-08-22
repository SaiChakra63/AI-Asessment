package com.shortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "url_stats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "url_id", nullable = false, unique = true)
    private UrlMapping urlMapping;

    @Builder.Default
    @Column(name = "click_count", nullable = false)
    private long clickCount = 0L;

    @Column(name = "last_accessed")
    private LocalDateTime lastAccessed;

    @Builder.Default
    @Column(name = "unique_visitors", nullable = false)
    private long uniqueVisitors = 0L;

    @Builder.Default
    @Column(name = "mobile_clicks", nullable = false)
    private long mobileClicks = 0L;

    @Builder.Default
    @Column(name = "desktop_clicks", nullable = false)
    private long desktopClicks = 0L;

    @Builder.Default
    @Column(name = "tablet_clicks", nullable = false)
    private long tabletClicks = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

}
