CREATE TABLE url_mappings (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(10) NOT NULL UNIQUE,
    original_url TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT valid_short_code CHECK (short_code ~ '^[a-zA-Z0-9]+$'),
    CONSTRAINT valid_url_length CHECK (LENGTH(original_url) BETWEEN 10 AND 2048)
);

CREATE TABLE url_stats (
    id BIGSERIAL PRIMARY KEY,
    url_id BIGINT NOT NULL UNIQUE REFERENCES url_mappings(id) ON DELETE CASCADE,
    click_count BIGINT NOT NULL DEFAULT 0 CHECK (click_count >= 0),
    last_accessed TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE url_analytics (
    id BIGSERIAL PRIMARY KEY,
    url_id BIGINT NOT NULL REFERENCES url_mappings(id) ON DELETE CASCADE,
    device_type VARCHAR(20),
    referrer VARCHAR(255),
    ip_address VARCHAR(45),
    user_agent TEXT,
    clicked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_url_mappings_active ON url_mappings(is_active);
CREATE INDEX idx_url_mappings_created_at ON url_mappings(created_at);
CREATE INDEX idx_url_analytics_url_id ON url_analytics(url_id);
CREATE INDEX idx_url_analytics_clicked_at ON url_analytics(clicked_at);
CREATE INDEX idx_url_analytics_device_type ON url_analytics(device_type);
