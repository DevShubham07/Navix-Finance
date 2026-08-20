-- V56 — optional password for an uploaded, password-protected document.
--
-- Banks routinely mail statements and payslips as encrypted PDFs (the key is usually a DOB/PAN
-- derivative the borrower knows and we do not). Before this the borrower had no way to hand that
-- key over, so a locked statement simply could not be reviewed. The upload screens now carry an
-- optional field and staff see the value beside the file.
--
-- Not a credential: it unlocks one document the borrower chose to give us, it is not an account
-- secret and it authenticates nothing. Stored as plain text on purpose — a reviewer has to be able
-- to read it back to open the file, so hashing would defeat the point. Nullable: almost every
-- upload leaves it empty.

alter table application_document add column if not exists file_password varchar(128);

comment on column application_document.file_password is
    'Optional password for a protected upload (borrower-supplied), shown to reviewing staff.';
