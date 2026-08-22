ALTER TABLE url_analytics
    ADD COLUMN city VARCHAR(100),
    ADD COLUMN country VARCHAR(100),
    ADD COLUMN continent VARCHAR(100),
    ADD COLUMN visitor_hash VARCHAR(64);

CREATE INDEX idx_url_analytics_referrer ON url_analytics(referrer);
CREATE INDEX idx_url_analytics_city ON url_analytics(city);
CREATE INDEX idx_url_analytics_country ON url_analytics(country);

ALTER TABLE url_stats
    ADD COLUMN unique_visitors BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN mobile_clicks BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN desktop_clicks BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tablet_clicks BIGINT NOT NULL DEFAULT 0;

CREATE TABLE url_unique_visitors (
    url_id BIGINT NOT NULL REFERENCES url_mappings(id) ON DELETE CASCADE,
    visitor_hash VARCHAR(64) NOT NULL,
    first_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (url_id, visitor_hash)
);

CREATE INDEX idx_unique_visitors_first_seen ON url_unique_visitors(first_seen);
