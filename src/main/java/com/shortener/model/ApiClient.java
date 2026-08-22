package com.shortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "api_clients")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiClient {

    @Id
    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(name = "api_key_digest", nullable = false, unique = true, length = 64)
    private String apiKeyDigest;

    @Column(nullable = false, length = 500)
    private String authorities;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
