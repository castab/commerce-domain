-- Aggregate counters are transactional rows, not PostgreSQL sequences. Holding this
-- row through commit prevents an uncommitted predecessor being overtaken on append.
CREATE TABLE commerce.outbox_aggregates (
    producer_id text NOT NULL CHECK (length(producer_id) BETWEEN 1 AND 200),
    aggregate_type text NOT NULL CHECK (length(aggregate_type) BETWEEN 1 AND 200),
    aggregate_id text NOT NULL CHECK (length(aggregate_id) BETWEEN 1 AND 200),
    last_sequence bigint NOT NULL CHECK (last_sequence > 0),
    PRIMARY KEY (producer_id, aggregate_type, aggregate_id)
);

CREATE TABLE commerce.outbox_events (
    id uuid PRIMARY KEY,
    producer_id text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_sequence bigint NOT NULL CHECK (aggregate_sequence > 0),
    event_type text NOT NULL CHECK (length(event_type) BETWEEN 1 AND 200),
    schema_version integer NOT NULL CHECK (schema_version > 0),
    destination text NOT NULL CHECK (length(destination) BETWEEN 1 AND 200),
    payload text NOT NULL,
    occurred_at timestamptz NOT NULL CHECK (isfinite(occurred_at)),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    attempts bigint NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claim_token uuid UNIQUE,
    claimed_by text CHECK (length(claimed_by) BETWEEN 1 AND 200),
    claimed_until timestamptz,
    last_failure_code text CHECK (last_failure_code IN ('UNCONFIRMED', 'TRANSPORT_EXCEPTION', 'TIMEOUT')),
    FOREIGN KEY (producer_id, aggregate_type, aggregate_id)
        REFERENCES commerce.outbox_aggregates (producer_id, aggregate_type, aggregate_id),
    UNIQUE (producer_id, aggregate_type, aggregate_id, aggregate_sequence),
    CHECK ((claim_token IS NULL AND claimed_by IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claimed_until IS NOT NULL)),
    CHECK (published_at IS NULL OR claim_token IS NULL)
);

CREATE INDEX outbox_pending_order ON commerce.outbox_events
    (producer_id, aggregate_type, aggregate_id, aggregate_sequence) WHERE published_at IS NULL;
CREATE INDEX outbox_pending_poll ON commerce.outbox_events
    (producer_id, next_attempt_at, recorded_at, id) WHERE published_at IS NULL;

-- Protect the historical envelope even from accidental repository UPDATEs.
CREATE FUNCTION commerce.preserve_outbox_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.producer_id, NEW.aggregate_type, NEW.aggregate_id,
           NEW.aggregate_sequence, NEW.event_type, NEW.schema_version, NEW.destination,
           NEW.payload, NEW.occurred_at, NEW.recorded_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.producer_id, OLD.aggregate_type, OLD.aggregate_id,
           OLD.aggregate_sequence, OLD.event_type, OLD.schema_version, OLD.destination,
           OLD.payload, OLD.occurred_at, OLD.recorded_at)
       OR (OLD.published_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'outbox event content and confirmed publication are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER preserve_outbox_event BEFORE UPDATE ON commerce.outbox_events
    FOR EACH ROW EXECUTE FUNCTION commerce.preserve_outbox_event();
