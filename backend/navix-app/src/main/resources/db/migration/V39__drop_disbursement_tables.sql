-- Drop the orphaned legacy disbursement tables.
-- The navix-disbursement module (UUID maker-checker chain) was superseded by the
-- single loan_application aggregate and has been deleted; these tables (created in
-- V2__core_schema.sql) now have no entity mapping. Dropping a table also drops its
-- indexes (idx_disbursement_request_loan_id, idx_approval_step_request_id).
drop table if exists approval_step;
drop table if exists disbursement_request;
