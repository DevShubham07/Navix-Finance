# DhanBoost — Frontend

Next.js (App Router) frontend for DhanBoost, a salary-linked single-repayment
lending product.

## Getting started

```bash
# 1. Configure environment
cp .env.example .env.local
# edit .env.local and set AUTH_SECRET, etc.

# 2. Install dependencies
npm install

# 3. Run the dev server (http://localhost:3000)
npm run dev
```

The frontend talks to the Spring Boot backend at `http://localhost:8080`.

## Route groups

The App Router uses two route groups to separate audiences:

- `(borrower)` — public/borrower-facing flows: landing page, login, signup
  (`/signup/pan`), loan application, repayment, etc.
- `(staff)` — internal back-office flows for the maker-checker roles:
  Credit Executive (review), Credit Head (final approve), Disbursement Head
  (release), and Accountant (confirms bank transfer to activate a loan).

Route groups in parentheses do not affect the URL path; they only organize
files and let each group have its own layout.

## Middleware & role gating

`src/middleware.ts` guards the `(staff)` routes. It reads the session cookie,
redirects unauthenticated users to `/login`, and redirects authenticated users
whose role does not permit a given staff route away from it. See the `matcher`
in `export const config`.
