-- Preserve valid historical JSON objects; invalid JSON or non-object documents fail
-- migration rather than silently replacing historical content. V2 remains published history.
ALTER TABLE commerce.outbox_events
    ALTER COLUMN payload TYPE jsonb USING payload::jsonb;

ALTER TABLE commerce.outbox_events
    ADD CONSTRAINT outbox_payload_object CHECK (jsonb_typeof(payload) = 'object');
