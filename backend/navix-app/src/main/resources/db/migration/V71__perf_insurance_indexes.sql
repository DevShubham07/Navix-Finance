-- V71 — four forward-looking indexes for the dashboard/collections/ledger reads.
--
-- None of these changes a number today. The UI performance investigation
-- (docs/perf/UI_PERFORMANCE_INVESTIGATION_2026-09-16.md §2.7) audited all 152 existing indexes
-- against the traced hot paths and found that every predicate that matters is already covered —
-- most of the "missing index" claims it checked were simply wrong. These four are the genuine gaps,
-- and at today's row counts (9.7k applications, 113 loans, 121 payments) the planner picks a
-- sequential scan either way. They are insurance: the access paths below are the ones that grow
-- with volume, and adding the index now is cheaper than diagnosing it later.
--
-- Each names the path it serves:
--   * application_event (action, at)      — DashboardService.trends, which runs
--                                           findByActionAndAtGreaterThanEqual('CREATE', since) on
--                                           every dashboard load; the event table only ever grows.
--   * loan (status, due_date)             — the collections worklist's findByStatusInAndDueDate…
--                                           (overdue + upcoming buckets).
--   * payment (status, paid_on)           — the trends scan over verified payments, and the new
--                                           accounting-ledger date window.
--   * loan_application (loan_id)          — findByLoanIdIn, the join back from a loan to its
--                                           application used by the Loans register, the collections
--                                           worklist and the ledger. Partial because most rows have
--                                           no loan: 9.7k applications, 113 loans.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration inside a transaction and
-- CONCURRENTLY cannot run there. At these table sizes the exclusive lock is milliseconds.

create index if not exists idx_application_event_action_at on application_event (action, at);

create index if not exists idx_loan_status_due_date on loan (status, due_date);

create index if not exists idx_payment_status_paid_on on payment (status, paid_on);

create index if not exists idx_loan_application_loan_id on loan_application (loan_id)
    where loan_id is not null;
