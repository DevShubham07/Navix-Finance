-- V25: optional receipt/attachment per company expense. Stores the S3 object key of an uploaded
-- file (bill / invoice / payment screenshot); the key is turned into a short-lived presigned URL on
-- read, never returned raw. Nullable — an expense may have no attachment.
alter table company_expense
    add column receipt_object_key varchar(512);
