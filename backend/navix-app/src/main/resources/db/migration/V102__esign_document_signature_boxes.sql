-- V102 - admin eSign documents: exact, drag-and-drop signature boxes replace the page-list + nine-preset
-- position. A signer now carries any number of boxes, each {page, x, y, width, height} in PDF points
-- measured from the TOP-LEFT of the page as displayed; they are stored as jsonb and sent to Signzy as
-- signaturePosition CUSTOMIZE placements.
--
-- Rows created under V101 keep their pages/position (the detail view falls back to them), so those
-- columns are relaxed to nullable rather than dropped. The position CHECK needs no change: a CHECK
-- passes on NULL, so new rows with a null position are accepted.
alter table esign_document_signer add column boxes jsonb;
alter table esign_document_signer alter column pages drop not null;
alter table esign_document_signer alter column position drop not null;
