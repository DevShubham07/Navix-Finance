# DocuSeal sanction-letter signing

## Goal

Replace the immediate/mock borrower e-sign completion in the post-sanction offer journey with a
DocuSeal-hosted, borrower-specific signing flow. A completed agreement is authoritative only after
a verified DocuSeal webhook; then the signed letter and audit trail are retained in S3 and the
existing offer journey can proceed to disbursement-account confirmation.

## Scope and decisions

- The document is the existing server-generated sanction-letter PDF, frozen when the borrower starts
  e-signing. It is not a manually maintained DocuSeal template.
- The borrower clicks E-sign and is redirected from DhanBoost to the private DocuSeal hosted signer
  URL. DocuSeal email/SMS delivery is disabled for this flow.
- One signing record exists per loan application. Repeated clicks reuse an unfinished submission;
  they do not create multiple agreements.
- Production uses DocuSeal when configured. The existing mock e-sign adapter remains the explicit
  local/demo fallback.

## Architecture

1. `OfferService` creates a frozen sanction-letter PDF and asks a DocuSeal client to create a
   submission from that PDF. It persists the provider submission and submitter identifiers, signer
   URL, source-PDF object key, status, and timestamps.
2. A borrower-authorized API returns the hosted signer URL. The existing e-sign page redirects the
   authenticated borrower to it.
3. DocuSeal redirects back to a DhanBoost completion page for progress feedback only. That page
   polls the normal journey/application API; a redirect or browser callback never marks a document
   signed.
4. The public webhook endpoint validates DocuSeal's HMAC signature, applies events idempotently,
   and only accepts the matching application/customer metadata. On `form.completed`, it downloads
   the signed PDF and audit log, stores both in S3, records the e-sign verification as passed, and
   advances the offer journey.
5. Existing handoff to `DISBURSEMENT_PENDING` continues to require the completed e-sign verification.

## Security and failure behavior

- `DOCUSEAL_API_KEY` and webhook secret stay backend-only (SSM/environment); they never reach the
  Next.js client. The key shared during setup must be rotated before production use.
- The signing URL is a per-submitter bearer URL, but the DhanBoost start endpoint additionally
  enforces borrower ownership. Submissions carry application and customer identifiers in metadata
  and are checked again on webhook receipt.
- Signed/PDF/audit download failures leave the completion pending and cause a non-2xx webhook
  response so DocuSeal retries. Duplicate webhooks are harmless.
- An unavailable DocuSeal provider returns a retryable user-facing error and does not alter the
  application state.

## Data, configuration, and APIs

- Add a Flyway migration for the signing-record table and indexes; do not alter historical
  migrations.
- Add typed configuration for enablement, API base URL, API key, webhook secret, redirect URL, and
  optional signer 2FA policy.
- Add a borrower start/status endpoint, a provider webhook endpoint, a typed frontend API client,
  and the hosted-redirect page behavior.

## Tests

- Unit-test idempotent submission creation, borrower ownership, missing configuration, and state
  gating.
- Unit-test HMAC validation, duplicate webhook handling, mismatched metadata rejection, and completed
  document archival/offer advancement.
- Verify existing offer tests still enforce that an unsigned agreement cannot reach disbursement.
