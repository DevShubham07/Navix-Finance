-- Credit brief (staff/internal only): the 1–5★ "should we recommend" rating, its verdict band, the
-- generated underwriter summary, when it was produced, and the parsed bureau facts (spec Categories
-- A/B/C) that back the brief card + PDF. The numeric credit score reuses the existing bureau_score.
ALTER TABLE applicant_profile
    ADD COLUMN credit_star_rating        NUMERIC(2,1),
    ADD COLUMN credit_recommendation     VARCHAR(40),
    ADD COLUMN credit_brief_summary      TEXT,
    ADD COLUMN credit_brief_generated_at TIMESTAMPTZ,
    ADD COLUMN credit_brief_facts        JSONB;
