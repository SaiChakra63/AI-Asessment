CREATE TABLE api_clients (
    client_id VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(150) NOT NULL,
    api_key_digest VARCHAR(64) NOT NULL UNIQUE,
    authorities VARCHAR(500) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT api_client_id_not_blank CHECK (LENGTH(TRIM(client_id)) > 0),
    CONSTRAINT api_client_digest_format CHECK (api_key_digest ~ '^[a-f0-9]{64}$')
);

-- Existing Phase 1/2 links remain publicly redirectable. They are assigned to
-- a disabled principal so they cannot be managed until an administrator
-- performs an explicit ownership migration.
INSERT INTO api_clients (
    client_id,
    display_name,
    api_key_digest,
    authorities,
    active
) VALUES (
    'legacy-system',
    'Legacy unmigrated URLs',
    '0000000000000000000000000000000000000000000000000000000000000000',
    'ADMIN',
    FALSE
);

ALTER TABLE url_mappings
    ADD COLUMN owner_client_id VARCHAR(100);

UPDATE url_mappings
SET owner_client_id = 'legacy-system'
WHERE owner_client_id IS NULL;

ALTER TABLE url_mappings
    ALTER COLUMN owner_client_id SET NOT NULL,
    ADD CONSTRAINT fk_url_mapping_owner
        FOREIGN KEY (owner_client_id) REFERENCES api_clients(client_id),
    ADD CONSTRAINT owner_client_id_not_blank
        CHECK (LENGTH(TRIM(owner_client_id)) > 0);

CREATE INDEX idx_url_mappings_owner_client
    ON url_mappings(owner_client_id);
