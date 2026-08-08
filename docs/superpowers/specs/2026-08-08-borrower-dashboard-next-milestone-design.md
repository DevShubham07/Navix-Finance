# Borrower Dashboard Next Milestone Card

## Goal

Replace the borrower dashboard's date-of-birth sidebar tile with a lifecycle-aware card that tells the borrower what happens next and, when action is possible, links directly to it.

## Scope

This is a frontend-only change to the borrower dashboard. It uses the application, loan, stage, due-date, and offer data already loaded by `useLiveApplication`; it does not add an API call or backend field. Date of birth remains available in Profile & KYC.

## Milestone mapping

| Borrower state | Card title | Supporting information | Action |
|---|---|---|---|
| No application or `DRAFT` | Complete your application | Finish the remaining onboarding steps. | Continue application → `/signup/start` |
| Live application before sanction | Credit decision | The application is with the review team. | Track status → `/loan/status` |
| `SANCTIONED` | Complete your offer steps | Finish the post-sanction steps before disbursal. | Continue loan → `/loan/amount` |
| `DISBURSEMENT_PENDING` or another release state | Disbursal in progress | The approved advance is being prepared for release. | Track status → `/loan/status` |
| `ACTIVE`, before salary date | Repayment due in N days | Show the formatted contractual due date. | Repay / prepay → `/repay` |
| `ACTIVE`, on salary date | Repayment due today | Show the formatted contractual due date. | Pay today → `/repay` |
| `ACTIVE`, on the following grace day | Grace day — pay today | Explain that payment is due today before late penalty begins. | Pay today → `/repay` |
| `OVERDUE` | Payment overdue by N days | Show the formatted contractual due date. | Pay now → `/repay` |
| `CLOSED` | You can borrow again | The previous advance is fully repaid. | Borrow again → `/reloan` |
| Rejected, cancelled, or another terminal unsuccessful state | Application closed | Direct the borrower to the recorded application status. | View status → `/loan/status` |

## UI behavior

The card keeps the existing sidebar position, border, background, spacing, and typography so the dashboard layout does not shift. It uses a lifecycle-appropriate icon and displays a compact text link beneath the supporting information. The milestone remains useful at mobile widths because the sidebar already stacks below the primary dashboard card.

Dates and day counts use the existing local date helpers. Grace day is exactly one calendar day after the contractual due date. An absent loan due date falls back to a status-oriented milestone rather than inventing a date.

## Code structure

Milestone selection will be a pure exported helper returning title, detail, icon kind, action label, and href. The dashboard component renders that result. Keeping state selection separate from JSX makes every lifecycle branch independently testable and prevents status logic from spreading through the layout.

## Error and fallback behavior

- Missing application data: show the onboarding milestone.
- Active application with missing loan or due date: show a track-status milestone.
- Unknown non-terminal status: show a track-status milestone using the existing application stage description.
- No DOB is shown on the dashboard; identity data remains unchanged elsewhere.

## Verification

Add focused Vitest coverage for onboarding, review, sanctioned, disbursal, active before/on due date, grace day, overdue, closed, rejected, and missing-date fallbacks. Run frontend Vitest, TypeScript, ESLint, and production build before committing and deploying.
