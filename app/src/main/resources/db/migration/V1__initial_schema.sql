-- =============================================================================
-- V1: Initial schema for the Notification POC business database
-- =============================================================================

-- Contacts table
CREATE TABLE IF NOT EXISTS contacts (
    id              UUID PRIMARY KEY,
    external_id_type VARCHAR(50) NOT NULL,
    external_id_value VARCHAR(255) NOT NULL,
    email           VARCHAR(255),
    phone           VARCHAR(50),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_contacts_external_id
    ON contacts (external_id_type, external_id_value);

-- Messages table
CREATE TABLE IF NOT EXISTS messages (
    id              UUID PRIMARY KEY,
    contact_id      UUID NOT NULL REFERENCES contacts(id),
    template_id     VARCHAR(100),
    channel         VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    content         TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_messages_contact_id ON messages (contact_id);

-- External events log (for audit/debugging)
CREATE TABLE IF NOT EXISTS external_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed       BOOLEAN NOT NULL DEFAULT FALSE
);
