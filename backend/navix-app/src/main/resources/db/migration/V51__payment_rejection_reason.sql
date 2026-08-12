-- A rejected payment now carries a reason, so the borrower learns why (IN_APP/EMAIL; the SMS body
-- stays DLT-locked and unchanged). reason is a fixed picklist enforced in RepaymentService, not a DB
-- check constraint (mirrors how PaymentMethod/PaymentStatus are enforced above the DB in this schema).

alter table payment add column rejection_reason varchar(64);
alter table payment add column rejection_note text;
