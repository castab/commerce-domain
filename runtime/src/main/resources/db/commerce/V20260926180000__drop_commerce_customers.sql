-- commerce-runtime no longer owns a customer model. Customers, and their relationships to
-- bookings and financial documents, are owned by concrete applications.
--
-- V20260926120000 created commerce.customers. Flyway history is immutable, so that
-- migration stays and this forward migration removes the table. A fresh installation
-- therefore creates and then drops it; the commerce schema and its history remain.
--
-- Safety for existing installations:
-- - The drop tolerates a database where the table is already gone.
-- - No CASCADE: if an application object (for example a foreign key) still depends on
--   commerce.customers, the migration fails instead of silently dropping that object.
-- - Rows are never discarded silently. If the table still holds customers, the migration
--   fails; move them into application-owned tables and delete them from
--   commerce.customers, then migrate again.
DO $$
BEGIN
    IF to_regclass('commerce.customers') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM commerce.customers) THEN
            RAISE EXCEPTION 'commerce.customers still contains rows. Customers are application-owned: move them into application tables and delete them from commerce.customers before applying this migration.';
        END IF;
    END IF;
END
$$;

DROP TABLE IF EXISTS commerce.customers;
