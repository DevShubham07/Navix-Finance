-- Optional borrower-supplied UAN (Universal Account Number, EPFO), 12 digits. When present it
-- gives the Digitap uan_basic employment lookup its direct method (method 3, docs/digitap/
-- UAN_EMPLOYMENT.md §2) instead of the PAN/mobile fallback; EMPLOYMENT stays advisory either way.
alter table customer_profile add column uan varchar(12);
