-- V100 — Remove statement PDF passwords from stored Bank Statement Analysis responses.
--
-- Signzy echoes each file's password back in its response, and response_json kept that envelope
-- verbatim. The client now masks credentials before the row is written; this cleans rows from before
-- that. jsonb::text always renders a key as "key": "value", so the pattern is exact.
update bank_statement_analysis
set response_json = regexp_replace(
        response_json::text,
        '"password": "([^"\\]|\\.)*"',
        '"password": "[REDACTED]"',
        'g')::jsonb
where response_json::text ~ '"password": "';
