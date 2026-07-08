/**
 * Barrel re-export for the staff live-pipeline building blocks.
 *
 * The former ~1150-line god-file was decomposed (Phase E) into focused modules
 * under `./pipeline/*`. This file re-exports their public surface unchanged so the
 * existing staff-page import sites keep compiling without edits. Prefer importing
 * directly from `./pipeline/*` in new code; import from this barrel only to avoid
 * churning existing call sites.
 *
 *  - hooks           — useStaffMe / fetchStaffMe / errMessage / useRefreshAfterAction /
 *                      ROLE_LABEL / PIPELINE_ROLES / REVIEW_PERMS / OPEN_LOAN_STATUSES / StaffMe
 *  - actions         — PermissionGate / NoAccessNotice + the 7 maker-checker action clusters
 *  - status-queue    — StatusQueue / CreditQueuePanel
 *  - app-row         — AppRow
 *  - customer-review — CustomerReview
 *  - loan-history    — LoanHistory
 *  - review-lookup   — ReviewLookup
 */

export * from "./pipeline/hooks";
export * from "./pipeline/actions";
export * from "./pipeline/status-queue";
export * from "./pipeline/app-row";
export * from "./pipeline/customer-review";
export * from "./pipeline/loan-history";
export * from "./pipeline/review-lookup";
