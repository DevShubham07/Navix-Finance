-- Freeze the outstanding calc for closed loans: CLOSED/REPAID loans currently keep accruing late
-- penalty against LocalDate.now() (RepaymentService.outstandingBreakdownAsOf), so a fully-repaid
-- historical loan reports a phantom balance. loan.closed_on lets that computation clamp its working
-- date to the day the loan actually closed.

alter table loan add column closed_on date;

-- Backfill: the day of the last VERIFIED payment on the loan, falling back to due_date when there is
-- no verified payment on record (best-effort reconstruction — see CLAUDE.md carve-out).
update loan l
set closed_on = coalesce(
    (select max(p.paid_on) from payment p where p.loan_id = l.id and p.status = 'VERIFIED'),
    l.due_date
)
where l.status in ('CLOSED', 'REPAID');
