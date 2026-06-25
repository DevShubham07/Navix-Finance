-- Records the bank/UPI transaction id for the outgoing disbursal. Captured when the Disbursement
-- Head releases funds directly (fast path) or when the accountant confirms the transfer. Surfaced
-- in the accountant's transactions ledger as the reference for OUTGOING (DISBURSAL) rows.
alter table loan add column disbursal_txn_ref varchar(64);
