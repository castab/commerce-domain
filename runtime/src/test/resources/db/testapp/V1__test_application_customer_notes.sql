-- An application-owned table, migrated from an application migration location after the
-- commerce migrations. It references a commerce-owned table, as a real application would.
CREATE TABLE public.test_application_customer_notes (
    customer_id uuid NOT NULL REFERENCES commerce.customers (id),
    note        text NOT NULL
);
