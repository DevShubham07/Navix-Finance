# Simulation Admin Environment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One designated account on the existing DhanBoost staff login opens a fully separate training copy of the business, with production left read-only throughout.

**Architecture:** A new isolated AWS stack (RDS restored from a copied backup, S3 bucket, ECS service, ALB behind CloudFront) runs the same backend with `NAVIX_ENV=sim` and no vendor credentials. The website's server routes only sessions marked `sim` to it, inside `proxyToBackend` plus the login and logout routes. SQL scripts lock the copy down and add made-up loans to reach the training figures.

**Tech Stack:** Next.js 15 route handlers, Vitest, Spring Boot 3.4 / Java 21, OpenPDF, PostgreSQL, AWS CLI v2 (RDS, S3, KMS, ECS Fargate, ALB, CloudFront, SSM).

**Spec:** `docs/superpowers/specs/2026-11-09-simulation-admin-environment-design.md`

## Global Constraints

- Production is read-only. Never connect to the production database. Never write to or reconfigure production RDS, S3, SSM, the `navix-backend` service or task definition, or `navix-alb`.
- Do not take a new snapshot of the production instance. Copy an existing automated backup only.
- No real data is copied before the owner confirms compliance sign-off (Task 9 gate).
- Nothing is pushed until the owner's explicit OK (Task 12 gate). A push to `main` deploys the website and backend to production through `.github/workflows/ci.yml`.
- All code work happens in a worktree built from `origin/main`. The `main` checkout at `C:\Users\gshub\Navix-Finance` is 173 commits behind live and must not be used for code.
- Domain, DNS and URLs do not change.
- Sim login email: `sim.admin@dhanboost.com`.
- Targets: total principal within ±1% of ₹70,00,000 (700,000,000 paise); average monthly closure 60% ±2 points.
- Money is integer paise: fee 10%, GST 18% of fee, interest 1% per day.
- Verify with real exit codes (`set -o pipefail`, `echo $?`), never grepped output.
- Run `npx tsc --noEmit` and ESLint on changed files before each frontend commit.
- Before review or handoff run `code-review-graph detect-changes --brief`.
- End every commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Commands use Git Bash. Use `C:/Users/gshub/Navix-Finance-sim` as the worktree path.

---

### Task 1: Create the worktree from the live commit

**Files:** none

**Interfaces:**
- Produces: worktree `C:/Users/gshub/Navix-Finance-sim` on branch `sim/training-environment`.

- [ ] **Step 1: Fetch and confirm the live commit**

```bash
cd /c/Users/gshub/Navix-Finance
git fetch origin main
aws ecs describe-task-definition --profile navix-dev --region ap-south-1 \
  --task-definition "$(aws ecs describe-services --profile navix-dev --region ap-south-1 --cluster navix-cluster --services navix-backend --query 'services[0].taskDefinition' --output text)" \
  --query 'taskDefinition.containerDefinitions[0].image' --output text
git rev-parse origin/main
```

Expected: the image tag equals the `origin/main` SHA, or is an ancestor of it (`git merge-base --is-ancestor <tag> origin/main; echo $?` prints 0).

- [ ] **Step 2: Create the worktree and install dependencies**

```bash
git worktree add ../Navix-Finance-sim -b sim/training-environment origin/main
cd ../Navix-Finance-sim/frontend && npm install --no-audit --no-fund; echo "exit=$?"
```

Expected: `exit=0`.

- [ ] **Step 3: Record the baseline**

```bash
cd /c/Users/gshub/Navix-Finance-sim/frontend
npx vitest run; echo "vitest=$?"
npx tsc --noEmit; echo "tsc=$?"
```

Expected: both print 0. If either fails on untouched code, stop and report it before changing anything.

---

### Task 2: Route marked sessions to the sim backend

**Files:**
- Create: `frontend/src/lib/api/sim-routing.ts`
- Modify: `frontend/src/lib/api/bff-proxy.ts`
- Modify: `frontend/src/lib/api/bff-session.ts`
- Test: `frontend/src/lib/api/bff-proxy.test.ts`

**Interfaces:**
- Produces:
  - `interface BackendTarget { baseUrl: string; headers: Record<string, string> }`
  - `simTarget(): BackendTarget | null`
  - `isSimLogin(email: string): boolean`
  - `targetForSession(session: { sim?: boolean } | null): BackendTarget`
  - `targetForToken(req: NextRequest, token: string): BackendTarget`
  - `StaffBffSession.sim?: true`

- [ ] **Step 1: Write the failing tests**

Replace the two existing import lines at the top of `frontend/src/lib/api/bff-proxy.test.ts` with:

```ts
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { proxyToBackend, rejectCrossSite } from "./bff-proxy";
import { isSimLogin, simTarget } from "./sim-routing";
```

Then append to the end of the file:

```ts
const SIM = "https://sim.example";

function enableSim() {
  vi.stubEnv("SIM_LOGIN_EMAIL", "sim.admin@dhanboost.com");
  vi.stubEnv("SIM_BACKEND_BASE_URL", SIM);
  vi.stubEnv("SIM_GATEWAY_KEY", "gateway-key");
}

function request(cookies: Record<string, unknown>) {
  const cookie = Object.entries(cookies)
    .map(([k, v]) => `${k}=${encodeURIComponent(JSON.stringify(v))}`)
    .join("; ");
  return new NextRequest("https://app.example/api/staff/loans", { headers: { cookie } });
}

function stubFetch() {
  const fetchMock = vi.fn(async () => new Response("{}", { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const calledUrl = (m: ReturnType<typeof stubFetch>) => String(m.mock.calls[0][0]);
const calledHeaders = (m: ReturnType<typeof stubFetch>) =>
  (m.mock.calls[0][1] as RequestInit).headers as Record<string, string>;

describe("proxyToBackend routing", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("sends a normal staff session to production with no gateway header", async () => {
    enableSim();
    const f = stubFetch();
    await proxyToBackend(request({ navix_staff: { token: "prod-jwt" } }), "/api/loans", "prod-jwt");
    expect(calledUrl(f).startsWith(SIM)).toBe(false);
    expect(calledHeaders(f)["X-Sim-Gateway"]).toBeUndefined();
  });

  it("sends a marked staff session to the sim with the gateway header", async () => {
    enableSim();
    const f = stubFetch();
    await proxyToBackend(request({ navix_staff: { token: "sim-jwt", sim: true } }), "/api/loans", "sim-jwt");
    expect(calledUrl(f)).toBe(`${SIM}/api/loans`);
    expect(calledHeaders(f)["X-Sim-Gateway"]).toBe("gateway-key");
  });

  it("keeps a borrower call on production when the browser also holds a sim staff session", async () => {
    enableSim();
    const f = stubFetch();
    const req = request({ navix_staff: { token: "sim-jwt", sim: true }, navix_borrower: { token: "b-jwt" } });
    await proxyToBackend(req, "/api/applications/mine", "b-jwt");
    expect(calledUrl(f).startsWith(SIM)).toBe(false);
  });

  it("stays on production when any sim setting is missing", async () => {
    vi.stubEnv("SIM_LOGIN_EMAIL", "sim.admin@dhanboost.com");
    vi.stubEnv("SIM_BACKEND_BASE_URL", SIM);
    vi.stubEnv("SIM_GATEWAY_KEY", "");
    const f = stubFetch();
    await proxyToBackend(request({ navix_staff: { token: "sim-jwt", sim: true } }), "/api/loans", "sim-jwt");
    expect(calledUrl(f).startsWith(SIM)).toBe(false);
    expect(simTarget()).toBeNull();
  });

  it("does not crash on a malformed staff cookie", async () => {
    enableSim();
    const f = stubFetch();
    const req = new NextRequest("https://app.example/x", { headers: { cookie: "navix_staff=not-json" } });
    await proxyToBackend(req, "/api/loans", "prod-jwt");
    expect(calledUrl(f).startsWith(SIM)).toBe(false);
  });
});

describe("isSimLogin", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("matches the designated email regardless of case and spaces", () => {
    enableSim();
    expect(isSimLogin("  SIM.Admin@DhanBoost.com ")).toBe(true);
  });

  it("does not match any other email", () => {
    enableSim();
    expect(isSimLogin("admin@dhanboost.com")).toBe(false);
  });

  it("is inert when not configured", () => {
    expect(isSimLogin("sim.admin@dhanboost.com")).toBe(false);
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /c/Users/gshub/Navix-Finance-sim/frontend && npx vitest run --project node src/lib/api/bff-proxy.test.ts`
Expected: FAIL with `Failed to resolve import "./sim-routing"`.

- [ ] **Step 3: Create the routing module**

Create `frontend/src/lib/api/sim-routing.ts`:

```ts
/**
 * Decides which backend a BFF request goes to. Only a staff session marked `sim` at login reaches the
 * simulation backend. Server-only: the SIM_* variables are never exposed to the browser.
 *
 * Fails closed: the two backends sign tokens with different secrets, so a token sent to the wrong one
 * (a forged cookie flag, or settings removed mid-session) is rejected with a 401.
 */

import type { NextRequest } from "next/server";
import { config } from "@/lib/config";

// Same literal as middleware.ts: importing bff-session here would pull next/headers into the proxy.
const STAFF_COOKIE = "navix_staff";

export interface BackendTarget {
  baseUrl: string;
  headers: Record<string, string>;
}

/** The sim backend, or null unless all three settings are present. */
export function simTarget(): BackendTarget | null {
  const email = process.env.SIM_LOGIN_EMAIL;
  const baseUrl = process.env.SIM_BACKEND_BASE_URL;
  const key = process.env.SIM_GATEWAY_KEY;
  if (!email || !baseUrl || !key) return null;
  return { baseUrl, headers: { "X-Sim-Gateway": key } };
}

export function isSimLogin(email: string): boolean {
  if (!simTarget()) return false;
  return email.trim().toLowerCase() === (process.env.SIM_LOGIN_EMAIL ?? "").trim().toLowerCase();
}

function production(): BackendTarget {
  return { baseUrl: config.backendBaseUrl, headers: {} };
}

export function targetForSession(session: { sim?: boolean } | null): BackendTarget {
  return (session?.sim === true ? simTarget() : null) ?? production();
}

/**
 * The target for a proxied call. Compares the token too: a browser can hold a sim staff session and a
 * real borrower session at once, and the borrower's calls must stay on production.
 */
export function targetForToken(req: NextRequest, token: string): BackendTarget {
  const raw = req.cookies.get(STAFF_COOKIE)?.value;
  if (!raw) return production();
  try {
    const staff = JSON.parse(raw) as { token?: string; sim?: boolean };
    return staff.token === token ? targetForSession(staff) : production();
  } catch {
    return production();
  }
}
```

- [ ] **Step 4: Use it in the proxy**

In `frontend/src/lib/api/bff-proxy.ts`, add the import:

```ts
import { targetForToken } from "@/lib/api/sim-routing";
```

Replace:

```ts
  const url = `${config.backendBaseUrl}${backendPath}${search}`;
```

with:

```ts
  const target = targetForToken(req, token);
  const url = `${target.baseUrl}${backendPath}${search}`;
```

Replace:

```ts
    "X-Request-Id": requestId,
  };
  const acting = req.headers.get("x-acting-role");
```

with:

```ts
    "X-Request-Id": requestId,
    ...target.headers,
  };
  const acting = req.headers.get("x-acting-role");
```

Remove the `import { config } from "@/lib/config";` line only if `config` is no longer referenced in the file.

In `frontend/src/lib/api/bff-session.ts`, replace:

```ts
  name: string;
  role: StaffRole;
}

export interface BorrowerBffSession {
```

with:

```ts
  name: string;
  role: StaffRole;
  /** Set only by the login route for the designated simulation account. */
  sim?: true;
}

export interface BorrowerBffSession {
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npx vitest run --project node src/lib/api/bff-proxy.test.ts; echo "exit=$?"`
Expected: all pass, `exit=0`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/api/sim-routing.ts frontend/src/lib/api/bff-proxy.ts frontend/src/lib/api/bff-session.ts frontend/src/lib/api/bff-proxy.test.ts
git commit -m "feat(sim): route marked staff sessions to the simulation backend" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Login, logout and session identity

**Files:**
- Modify: `frontend/src/app/api/auth/staff/login/route.ts`
- Modify: `frontend/src/app/api/auth/staff/logout/route.ts`
- Modify: `frontend/src/app/api/auth/staff/me/route.ts`
- Test: `frontend/src/app/api/auth/staff/sim-auth.test.ts`

**Interfaces:**
- Consumes: `simTarget`, `isSimLogin`, `targetForSession` from Task 2.
- Produces: `GET /api/auth/staff/me` returns `session.sim: boolean`.

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/app/api/auth/staff/sim-auth.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

const session = vi.hoisted(() => ({
  setStaffSession: vi.fn(),
  getStaffSession: vi.fn(),
  clearStaffSession: vi.fn(),
}));
vi.mock("@/lib/api/bff-session", () => session);

import { POST as login } from "./login/route";
import { POST as logout } from "./logout/route";
import { GET as me } from "./me/route";

const SIM = "https://sim.example";

function loginRequest(email: string) {
  return new NextRequest("https://app.example/api/auth/staff/login", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password: "secret-password" }),
  });
}

function stubLoginResponse() {
  const f = vi.fn(async () =>
    new Response(JSON.stringify({ data: { token: "jwt", id: 1, name: "A", role: "ADMIN", customerId: null } }), {
      status: 200,
    }),
  );
  vi.stubGlobal("fetch", f);
  return f;
}

describe("staff auth routing", () => {
  beforeEach(() => {
    vi.stubEnv("SIM_LOGIN_EMAIL", "sim.admin@dhanboost.com");
    vi.stubEnv("SIM_BACKEND_BASE_URL", SIM);
    vi.stubEnv("SIM_GATEWAY_KEY", "gateway-key");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("logs a normal account in on production and does not mark the session", async () => {
    const f = stubLoginResponse();
    await login(loginRequest("admin@dhanboost.com"));
    expect(String(f.mock.calls[0][0]).startsWith(SIM)).toBe(false);
    expect(session.setStaffSession.mock.calls[0][0].sim).toBeUndefined();
  });

  it("logs the designated account in on the sim and marks the session", async () => {
    const f = stubLoginResponse();
    await login(loginRequest("sim.admin@dhanboost.com"));
    expect(String(f.mock.calls[0][0])).toBe(`${SIM}/api/auth/staff/login`);
    expect((f.mock.calls[0][1] as RequestInit).headers).toMatchObject({ "X-Sim-Gateway": "gateway-key" });
    expect(session.setStaffSession.mock.calls[0][0].sim).toBe(true);
  });

  it("never returns the token from login", async () => {
    stubLoginResponse();
    const res = await login(loginRequest("sim.admin@dhanboost.com"));
    expect(await res.json()).not.toHaveProperty("token");
  });

  it("logs a sim session out on the sim", async () => {
    session.getStaffSession.mockResolvedValue({ token: "jwt", sim: true });
    const f = vi.fn(async () => new Response("{}"));
    vi.stubGlobal("fetch", f);
    await logout();
    expect(String(f.mock.calls[0][0])).toBe(`${SIM}/api/auth/staff/logout`);
  });

  it("logs a normal session out on production", async () => {
    session.getStaffSession.mockResolvedValue({ token: "jwt" });
    const f = vi.fn(async () => new Response("{}"));
    vi.stubGlobal("fetch", f);
    await logout();
    expect(String(f.mock.calls[0][0]).startsWith(SIM)).toBe(false);
  });

  it("reports sim in /me without exposing the token", async () => {
    session.getStaffSession.mockResolvedValue({ token: "jwt", id: "1", name: "A", role: "ADMIN", sim: true });
    const body = await (await me()).json();
    expect(body.session.sim).toBe(true);
    expect(body.session.token).toBeUndefined();
  });

  it("reports sim false in /me for a normal session", async () => {
    session.getStaffSession.mockResolvedValue({ token: "jwt", id: "1", name: "A", role: "ADMIN" });
    const body = await (await me()).json();
    expect(body.session.sim).toBe(false);
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run --project node src/app/api/auth/staff/sim-auth.test.ts`
Expected: FAIL. The sim login assertions report a production URL and `sim` undefined.

- [ ] **Step 3: Update the login route**

In `frontend/src/app/api/auth/staff/login/route.ts`, add:

```ts
import { isSimLogin, simTarget } from "@/lib/api/sim-routing";
```

Replace:

```ts
  const loginPassword = password;

  let backendRes: Response;
  try {
    backendRes = await fetch(`${config.backendBaseUrl}/api/auth/staff/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
```

with:

```ts
  const loginPassword = password;
  // Only the designated account leaves the production path.
  const sim = isSimLogin(loginEmail) ? simTarget() : null;

  let backendRes: Response;
  try {
    backendRes = await fetch(`${sim?.baseUrl ?? config.backendBaseUrl}/api/auth/staff/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json", ...sim?.headers },
```

Replace:

```ts
  const session = { token: data.token, id: String(data.id), name: data.name, role: data.role };
```

with:

```ts
  const session = {
    token: data.token,
    id: String(data.id),
    name: data.name,
    role: data.role,
    ...(sim ? { sim: true as const } : {}),
  };
```

- [ ] **Step 4: Update the logout route**

Replace the contents of `frontend/src/app/api/auth/staff/logout/route.ts`, keeping the existing doc comment:

```ts
import { NextResponse } from "next/server";
import { clearStaffSession, getStaffSession } from "@/lib/api/bff-session";
import { targetForSession } from "@/lib/api/sim-routing";

/**
 * Clear the staff session cookie — and tell the backend first, so `staff_user.active_session_id`
 * is cleared too. Without this a clean sign-out left the session "live" server-side, forcing the
 * staffer's NEXT login to pass `force` even though nothing was actually still signed in.
 */
export async function POST() {
  const session = await getStaffSession();
  if (session) {
    const target = targetForSession(session);
    try {
      await fetch(`${target.baseUrl}/api/auth/staff/logout`, {
        method: "POST",
        headers: { Authorization: `Bearer ${session.token}`, Accept: "application/json", ...target.headers },
        cache: "no-store",
      });
    } catch {
      // Best-effort: the cookie still gets cleared below either way, and the session's TTL/next
      // request will settle it (e.g. a forced login elsewhere clears it too).
    }
  }
  await clearStaffSession();
  return NextResponse.json({ ok: true });
}
```

- [ ] **Step 5: Update the identity route**

In `frontend/src/app/api/auth/staff/me/route.ts`, replace:

```ts
    session: session ? { id: session.id, name: session.name, role: session.role } : null,
```

with:

```ts
    session: session ? { id: session.id, name: session.name, role: session.role, sim: session.sim === true } : null,
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `npx vitest run --project node src/app/api/auth/staff/sim-auth.test.ts; echo "exit=$?"`
Expected: all pass, `exit=0`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/api/auth/staff
git commit -m "feat(sim): send the designated login to the simulation backend" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Permanent SIMULATION banner

**Files:**
- Create: `frontend/src/components/staff/simulation-banner.tsx`
- Modify: `frontend/src/lib/auth/staff-session.ts`
- Modify: `frontend/src/components/staff/staff-shell.tsx`
- Test: `frontend/src/components/staff/simulation-banner.test.tsx`

**Interfaces:**
- Consumes: `session.sim` from `/api/auth/staff/me` (Task 3).
- Produces: `StaffSession.sim?: boolean`; `SimulationBanner` component.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/staff/simulation-banner.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SimulationBanner } from "./simulation-banner";

describe("SimulationBanner", () => {
  it("states that the console is a simulation with no way to dismiss it", () => {
    render(<SimulationBanner />);
    expect(screen.getByRole("status")).toHaveTextContent("SIMULATION");
    expect(screen.getByRole("status")).toHaveTextContent("not real company performance");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx vitest run --project dom src/components/staff/simulation-banner.test.tsx`
Expected: FAIL with `Failed to resolve import "./simulation-banner"`.

- [ ] **Step 3: Create the banner**

Create `frontend/src/components/staff/simulation-banner.tsx`:

```tsx
/** Always-visible strip for a simulation session. Deliberately has no dismiss control. */
export function SimulationBanner() {
  return (
    <div
      role="status"
      className="pointer-events-none fixed inset-x-0 bottom-0 z-50 bg-error-500 px-4 py-1.5 text-center text-xs font-bold tracking-wide text-white"
    >
      SIMULATION — training data only. Figures shown here are not real company performance.
    </div>
  );
}
```

- [ ] **Step 4: Wire it into the shell**

In `frontend/src/lib/auth/staff-session.ts`, replace:

```ts
  /** The role on the session cookie. */
  realRole: StaffRole;
}
```

with:

```ts
  /** The role on the session cookie. */
  realRole: StaffRole;
  /** True only for the designated simulation account. */
  sim?: boolean;
}
```

In `frontend/src/components/staff/staff-shell.tsx`, add the import:

```tsx
import { SimulationBanner } from "@/components/staff/simulation-banner";
```

Replace:

```tsx
      <Toaster />
```

with:

```tsx
      {session.sim && <SimulationBanner />}
      <Toaster />
```

- [ ] **Step 5: Run the test and typecheck**

Run: `npx vitest run --project dom src/components/staff/simulation-banner.test.tsx; echo "vitest=$?"; npx tsc --noEmit; echo "tsc=$?"`
Expected: `vitest=0`, `tsc=0`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/staff/simulation-banner.tsx frontend/src/components/staff/simulation-banner.test.tsx frontend/src/lib/auth/staff-session.ts frontend/src/components/staff/staff-shell.tsx
git commit -m "feat(sim): show a permanent simulation banner in the staff console" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Label browser exports

**Files:**
- Create: `frontend/src/lib/export/simulation-mode.ts`
- Modify: `frontend/src/lib/export/exporters.ts`
- Modify: `frontend/src/components/staff/staff-shell.tsx`
- Test: `frontend/src/lib/export/exporters.test.ts`

**Interfaces:**
- Consumes: `StaffSession.sim` (Task 4).
- Produces: `setExportSimulation(on: boolean): void` and `isExportSimulation(): boolean` in `simulation-mode.ts`; exported `drawPdfFooter(doc: jsPDF): void`.

The flag lives in its own module because the staff shell loads on every page, and importing `exporters.ts` there would pull jsPDF into every staff page's bundle.

- [ ] **Step 1: Write the failing tests**

In `frontend/src/lib/export/exporters.test.ts`, change the imports to:

```ts
import { afterEach, describe, expect, it, vi } from "vitest";
import type { jsPDF } from "jspdf";
import { csvCell, drawPdfFooter, exportCsvText } from "@/lib/export/exporters";
import { setExportSimulation } from "@/lib/export/simulation-mode";
```

Append:

```ts
function captureDownload() {
  const anchor = { href: "", download: "", click: vi.fn(), remove: vi.fn() };
  vi.stubGlobal("document", { createElement: () => anchor, body: { appendChild: vi.fn() } });
  let blob: Blob | undefined;
  vi.spyOn(URL, "createObjectURL").mockImplementation((b) => {
    blob = b as Blob;
    return "blob:x";
  });
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
  return { anchor, text: () => blob!.text() };
}

function fakeDoc() {
  return {
    internal: { pageSize: { getWidth: () => 800, getHeight: () => 600 } },
    getNumberOfPages: () => 1,
    setPage: vi.fn(),
    setFont: vi.fn(),
    setFontSize: vi.fn(),
    setTextColor: vi.fn(),
    text: vi.fn(),
  };
}

const footerText = (d: ReturnType<typeof fakeDoc>) => d.text.mock.calls.map((c) => String(c[0]));

describe("simulation labels", () => {
  afterEach(() => {
    setExportSimulation(false);
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("labels a CSV and its filename in simulation mode", async () => {
    setExportSimulation(true);
    const d = captureDownload();
    exportCsvText("loans", "a,b\n1,2");
    expect(d.anchor.download).toBe("SIMULATION-loans.csv");
    expect(await d.text()).toContain("SIMULATION - NOT REAL DATA\na,b");
  });

  it("leaves a normal CSV unchanged", async () => {
    const d = captureDownload();
    exportCsvText("loans", "a,b\n1,2");
    expect(d.anchor.download).toBe("loans.csv");
    expect(await d.text()).not.toContain("SIMULATION");
  });

  it("labels every PDF page in simulation mode", () => {
    setExportSimulation(true);
    const d = fakeDoc();
    drawPdfFooter(d as unknown as jsPDF);
    expect(footerText(d)).toContain("SIMULATION - NOT REAL DATA");
  });

  it("does not label a normal PDF", () => {
    const d = fakeDoc();
    drawPdfFooter(d as unknown as jsPDF);
    expect(footerText(d)).not.toContain("SIMULATION - NOT REAL DATA");
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run --project node src/lib/export/exporters.test.ts`
Expected: FAIL with `Failed to resolve import "@/lib/export/simulation-mode"`.

- [ ] **Step 3: Implement the labels**

Create `frontend/src/lib/export/simulation-mode.ts`:

```ts
/** Set by the staff shell from the session, so every export path is covered in one place. */
let simulationExports = false;

export function setExportSimulation(on: boolean): void {
  simulationExports = on;
}

export function isExportSimulation(): boolean {
  return simulationExports;
}
```

In `frontend/src/lib/export/exporters.ts`, add the import:

```ts
import { isExportSimulation } from "./simulation-mode";
```

Immediately above `function triggerDownload`, add:

```ts
const SIMULATION_LABEL = "SIMULATION - NOT REAL DATA";

function exportName(filename: string): string {
  return isExportSimulation() ? `SIMULATION-${filename}` : filename;
}
```

Replace `  a.download = filename;` with `  a.download = exportName(filename);`.

In `exportCsvText`, replace:

```ts
  triggerDownload("﻿" + csv, `${fileBase}.csv`, "text/csv;charset=utf-8;");
```

with:

```ts
  const labelled = isExportSimulation() ? `${SIMULATION_LABEL}\n${csv}` : csv;
  triggerDownload("﻿" + labelled, `${fileBase}.csv`, "text/csv;charset=utf-8;");
```

Replace `function drawPdfFooter(doc: jsPDF): void {` with `export function drawPdfFooter(doc: jsPDF): void {`.

Inside its loop, replace:

```ts
    doc.text(`Page ${i} of ${pageCount}`, pageWidth - 40, pageHeight - 18, { align: "right" });
```

with:

```ts
    doc.text(`Page ${i} of ${pageCount}`, pageWidth - 40, pageHeight - 18, { align: "right" });
    if (isExportSimulation()) {
      doc.setFont("helvetica", "bold");
      doc.setFontSize(11);
      doc.setTextColor(178, 34, 34);
      doc.text(SIMULATION_LABEL, pageWidth / 2, pageHeight - 18, { align: "center" });
    }
```

Route all five PDF saves through the filename helper:

```bash
sed -i -E 's/doc\.save\((`[^`]+`)\)/doc.save(exportName(\1))/' src/lib/export/exporters.ts
grep -c 'doc.save(exportName(' src/lib/export/exporters.ts
```

Expected: `5`.

In `frontend/src/components/staff/staff-shell.tsx`, add the import:

```tsx
import { setExportSimulation } from "@/lib/export/simulation-mode";
```

Replace:

```tsx
  const authed = !isPublic && !loading && !!session;
```

with:

```tsx
  const authed = !isPublic && !loading && !!session;
  React.useEffect(() => {
    setExportSimulation(session?.sim === true);
  }, [session?.sim]);
```

- [ ] **Step 4: Run the tests and typecheck**

Run: `npx vitest run; echo "vitest=$?"; npx tsc --noEmit; echo "tsc=$?"; npx eslint src/lib/export src/lib/api src/components/staff/staff-shell.tsx src/app/api/auth/staff; echo "eslint=$?"`
Expected: all three print 0.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/export frontend/src/components/staff/staff-shell.tsx
git commit -m "feat(sim): label browser exports in simulation sessions" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Label backend PDFs

**Files:**
- Create: `backend/navix-loan/src/main/java/com/navix/loan/pdf/SimulationStamp.java`
- Modify: `backend/navix-loan/src/main/java/com/navix/loan/pdf/SanctionLetterPdfRenderer.java` (the `PdfWriter.getInstance(doc, out);` line)
- Modify: `backend/navix-loan/src/main/java/com/navix/loan/pdf/CreditBriefPdfRenderer.java` (the `PdfWriter.getInstance(doc, out);` line)
- Test: `backend/navix-loan/src/test/java/com/navix/loan/pdf/SimulationStampTest.java`

**Interfaces:**
- Produces: `SimulationStamp.apply(PdfWriter writer)` and `SimulationStamp.apply(PdfWriter writer, String env)`.

- [ ] **Step 1: Write the failing test**

Create `backend/navix-loan/src/test/java/com/navix/loan/pdf/SimulationStampTest.java`:

```java
package com.navix.loan.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class SimulationStampTest {

    private static String firstPageText(String env) throws Exception {
        Document doc = new Document(PageSize.A4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SimulationStamp.apply(PdfWriter.getInstance(doc, out), env);
        doc.open();
        doc.add(new Paragraph("Body"));
        doc.close();
        PdfReader reader = new PdfReader(out.toByteArray());
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        return text;
    }

    @Test
    void stampsPagesInTheSimulationEnvironment() throws Exception {
        assertThat(firstPageText("sim")).contains(SimulationStamp.LABEL);
    }

    @Test
    void leavesOtherEnvironmentsUnchanged() throws Exception {
        assertThat(firstPageText("dev")).doesNotContain(SimulationStamp.LABEL);
        assertThat(firstPageText("prod")).doesNotContain(SimulationStamp.LABEL);
        assertThat(firstPageText(null)).doesNotContain(SimulationStamp.LABEL);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd /c/Users/gshub/Navix-Finance-sim/backend
java -version
./mvnw -q -pl navix-loan -am test -Dtest=SimulationStampTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `java -version` reports 21, and the build FAILS with `cannot find symbol: SimulationStamp`.

- [ ] **Step 3: Implement the stamp**

Create `backend/navix-loan/src/main/java/com/navix/loan/pdf/SimulationStamp.java`:

```java
package com.navix.loan.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;

/** Marks generated PDFs in the training environment. A no-op unless {@code NAVIX_ENV=sim}. */
final class SimulationStamp {

    static final String LABEL = "SIMULATION - NOT A REAL DOCUMENT";
    private static final Font FONT = new Font(Font.HELVETICA, 11, Font.BOLD, new Color(178, 34, 34));

    private SimulationStamp() {
    }

    static void apply(PdfWriter writer) {
        apply(writer, System.getenv("NAVIX_ENV"));
    }

    static void apply(PdfWriter writer, String env) {
        if (!"sim".equalsIgnoreCase(env)) {
            return;
        }
        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter w, Document d) {
                ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_CENTER, new Phrase(LABEL, FONT),
                        d.getPageSize().getWidth() / 2, d.getPageSize().getHeight() - 14, 0);
            }
        });
    }
}
```

In both `SanctionLetterPdfRenderer.java` and `CreditBriefPdfRenderer.java`, replace:

```java
            PdfWriter.getInstance(doc, out);
```

with:

```java
            SimulationStamp.apply(PdfWriter.getInstance(doc, out));
```

- [ ] **Step 4: Run the PDF tests**

Run: `./mvnw -q -pl navix-loan -am test -Dtest='SimulationStampTest,SanctionLetterPdfRendererTest,CreditBriefPdfRendererTest' -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=0`.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-loan/src
git commit -m "feat(sim): stamp generated PDFs in the simulation environment" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Data scripts, tested locally

Uses only the local Docker database (`navix-postgres` on port 5433). Nothing here touches AWS.

**Files:**
- Create: `scripts/sim/sim-run.sh`
- Create: `scripts/sim/10_prepare.sql`
- Create: `scripts/sim/20_training_loans.sql`
- Create: `scripts/sim/30_verify.sql`
- Create: `scripts/sim/Dockerfile`
- Test: `scripts/sim/test/fixture.sql`, `scripts/sim/test/run-local.sh`

**Interfaces:**
- Produces: `sim-run.sh prepare|loans|verify`. Environment: `PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `SIM_APP_DB_PASSWORD`, `SIM_ADMIN_PASSWORD`, and `SIM_ALLOW_LOCAL=1` for local tests only.

- [ ] **Step 1: Write the failing local test**

Create `scripts/sim/test/fixture.sql`:

```sql
-- Stands in for real production rows: a small book over the last three completed months.
create role navix_app login password 'old';

insert into loan (customer_id, principal, processing_fee, gst, net_disbursed, daily_interest_rate,
                  disbursed_on, due_date, total_repayable, outstanding, status, created_at, closed_on)
select 100 + g, 1000000, 100000, 18000, 882000, 0.01,
       (date_trunc('month', current_date) - (g % 3 + 1) * interval '1 month')::date + 2,
       (date_trunc('month', current_date) - (g % 3 + 1) * interval '1 month')::date + 20,
       1180000, case when g % 2 = 0 then 0 else 1180000 end,
       case when g % 2 = 0 then 'CLOSED' else 'OVERDUE' end, now(),
       case when g % 2 = 0 then (date_trunc('month', current_date) - (g % 3 + 1) * interval '1 month')::date + 20 end
from generate_series(1, 12) g;

insert into loan_application (customer_id, status, loan_id, created_at)
select customer_id, case when status = 'CLOSED' then 'CLOSED' else 'OVERDUE' end, id, now() from loan;
```

Create `scripts/sim/test/run-local.sh`:

```bash
#!/usr/bin/env bash
# Exercises the sim SQL against a throwaway local database built from the real migrations.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
export PGHOST=localhost PGPORT=5433 PGUSER=navix PGPASSWORD=navix SIM_ALLOW_LOCAL=1
export SIM_APP_DB_PASSWORD=new-app-password SIM_ADMIN_PASSWORD=training-password-1
RUN="$ROOT/scripts/sim/sim-run.sh"
DB=navix_simtest

fresh() {
  psql -q -X -d postgres -c "drop database if exists $DB" -c "drop role if exists navix_app" -c "create database $DB"
  export PGDATABASE=$DB
  for f in $(ls "$ROOT"/backend/navix-app/src/main/resources/db/migration/V*.sql | sort -V); do
    psql -q -X -v ON_ERROR_STOP=1 -f "$f" >/dev/null
  done
  psql -q -X -v ON_ERROR_STOP=1 -f "$ROOT/scripts/sim/test/fixture.sql"
}

val() { psql -X -At -c "$1"; }
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1" >&2; exit 1; }

fresh
if "$RUN" loans 2>/dev/null; then fail "loans ran without a sim marker"; fi
pass "loans refuses a database with no sim marker"

if PGHOST=navix-finance-dev.cf22os0umu8l.ap-south-1.rds.amazonaws.com "$RUN" prepare 2>/dev/null; then fail "ran against production host"; fi
pass "wrapper refuses the production host"

real_before=$(val "select count(*) from loan")
"$RUN" prepare
[ "$(val "select count(*) from staff_user where status='ACTIVE'")" = 1 ] || fail "more than one active staff user"
[ "$(val "select role from staff_user where status='ACTIVE'")" = ADMIN ] || fail "active user is not ADMIN"
[ "$(val "select email from staff_user where status='ACTIVE'")" = sim.admin@dhanboost.com ] || fail "wrong active email"
[ "$(val "select password_hash = crypt('training-password-1', password_hash) from staff_user where status='ACTIVE'")" = t ] || fail "bad password hash"
pass "prepare locks staff down to the one admin"

"$RUN" loans
"$RUN" verify
[ "$(val "select count(*) from loan where created_by <> 'sim-seed' or created_by is null")" = "$real_before" ] || fail "real loan count changed"
[ "$(val "select count(*) from loan where created_by is null and principal <> 1000000")" = 0 ] || fail "a real loan was edited"
[ "$(val "select count(*) from customer_profile where mobile not like '555%' and full_name like 'Sim Trainee %'")" = 0 ] || fail "trainee with a plausible mobile"
for s in CREDIT_EXEC_PENDING SANCTIONED DISBURSEMENT_PENDING; do
  [ "$(val "select count(*) from loan_application where status='$s'")" -ge 5 ] || fail "too few $s files"
done
[ "$(val "select count(*) from payment where status='PENDING_VERIFICATION'")" -ge 5 ] || fail "too few repayments awaiting verification"
pass "training loans meet the targets and leave real rows alone"

if "$RUN" loans 2>/dev/null; then fail "loans ran twice"; fi
pass "loans refuses a second run"

fresh
"$RUN" prepare
psql -q -X -c "update loan set principal = 80000000 where id = (select min(id) from loan)"
psql -q -X -c "update loan set principal = 650000000 where id = (select max(id) from loan)"
before=$(val "select count(*) from loan")
if "$RUN" loans 2>/dev/null; then fail "loans ran with the book above target"; fi
[ "$(val "select count(*) from loan")" = "$before" ] || fail "partial insert left behind"
pass "stops without changes when the real book exceeds the target"

echo "ALL PASSED"
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /c/Users/gshub/Navix-Finance-sim && bash scripts/sim/test/run-local.sh; echo "exit=$?"`
Expected: non-zero exit. Migrations apply, then the first `$RUN` fails because `sim-run.sh` does not exist, which trips the `set -e` path or a `FAIL` line. If a migration fails under `psql`, stop and fix the runner before continuing.

- [ ] **Step 3: Create the wrapper**

Create `scripts/sim/sim-run.sh`:

```sh
#!/bin/sh
# Runs the simulation SQL. Refuses any database host other than the simulation instance.
set -eu

case "${PGHOST:-}" in
  navix-finance-sim.*) ;;
  localhost)
    [ "${SIM_ALLOW_LOCAL:-}" = 1 ] || { echo "REFUSED: localhost needs SIM_ALLOW_LOCAL=1" >&2; exit 2; }
    ;;
  *)
    echo "REFUSED: ${PGHOST:-<unset>} is not the simulation database" >&2
    exit 2
    ;;
esac

DIR=$(cd "$(dirname "$0")" && pwd)
PSQL="psql -X -q -v ON_ERROR_STOP=1"

case "${1:-}" in
  prepare) $PSQL -v app_pw="$SIM_APP_DB_PASSWORD" -v admin_pw="$SIM_ADMIN_PASSWORD" -f "$DIR/10_prepare.sql" ;;
  loans)   $PSQL -f "$DIR/20_training_loans.sql" ;;
  verify)  $PSQL -f "$DIR/30_verify.sql" ;;
  *) echo "usage: sim-run.sh prepare|loans|verify" >&2; exit 64 ;;
esac
```

- [ ] **Step 4: Create the prepare script**

Create `scripts/sim/10_prepare.sql`:

```sql
-- Runs first on a freshly restored copy. The marker is what every later script checks for.
begin;

create table sim_marker (created_at timestamptz not null default now());
insert into sim_marker default values;

-- The restored copy still carries production's application password.
alter role navix_app password :'app_pw';

do $$
declare r record;
begin
  for r in select rolname from pg_roles
           where rolcanlogin and rolname not like 'rds%' and rolname not in (current_user, 'navix_app')
  loop
    execute format('alter role %I nologin', r.rolname);
  end loop;
end $$;

update staff_user set status = 'DISABLED', active_session_id = null, active_session_at = null;
delete from password_reset_token;
delete from staff_invite where accepted_at is null;

update feature_flag set enabled = false where flag_key in ('digio-mandate', 'digio-debit', 'payu-repayment');

create extension if not exists pgcrypto;
insert into staff_user (email, name, role, status, password_hash, created_at, created_by)
values ('sim.admin@dhanboost.com', 'Training Admin', 'ADMIN', 'ACTIVE',
        crypt(:'admin_pw', gen_salt('bf', 10)), now(), 'sim-seed');

commit;
```

- [ ] **Step 5: Create the training-loans script**

Create `scripts/sim/20_training_loans.sql`:

```sql
-- Adds made-up borrowers until the book shows the training targets. Never edits an existing row.
-- One transaction: if the targets cannot be met, nothing is left behind.
begin;

do $$
begin
  if to_regclass('public.sim_marker') is null then
    raise exception 'REFUSED: not the simulation database (sim_marker missing)';
  end if;
  if exists (select 1 from customer_profile where full_name like 'Sim Trainee %') then
    raise exception 'Training loans already exist. Restore the copy again to reset.';
  end if;
end $$;

create function pg_temp.sim_application(p_n int, p_status text, p_principal bigint, p_loan bigint,
                                        p_created timestamptz, p_due date)
returns bigint language plpgsql as $f$
declare
  v_app bigint;
  v_customer bigint := 900000000 + p_n;
  v_mobile text := (5550000000::bigint + p_n)::text;
  v_salary bigint := p_principal * 5;
  v_sanctioned boolean := p_status <> 'CREDIT_EXEC_PENDING';
  v_path text[] := array['DRAFT', 'KYC_PENDING', 'CREDIT_EXEC_PENDING', 'SANCTIONED', 'DISBURSEMENT_PENDING',
                         'DISBURSED', 'ACTIVE', 'OVERDUE', 'CLOSED'];
  v_last int := array_position(v_path, p_status);
  v_prev text := null;
  v_step int := 0;
  v_to text;
begin
  insert into loan_application (customer_id, amount_requested, eligible_limit, status, purpose, loan_id,
                                salary_credit_day, sanctioned_amount_paise, approved_repayment_date,
                                sanction_tenure_days, sanctioned_at, disbursal_account_number, disbursal_ifsc,
                                disbursal_holder_name, disbursal_bank, disbursal_account_changed,
                                disbursal_account_verified, created_at)
  values (v_customer, p_principal, floor(v_salary * 0.25 / 10000)::bigint * 10000, p_status, 'Training case', p_loan,
          least(extract(day from p_due)::int, 28),
          case when v_sanctioned then p_principal end,
          case when v_sanctioned then p_due end,
          case when v_sanctioned then greatest(p_due - p_created::date, 1) end,
          case when v_sanctioned then p_created + interval '1 day' end,
          '000000' || p_n, 'SIMB0000001', 'Sim Trainee ' || lpad(p_n::text, 4, '0'), 'Sample Bank',
          false, true, p_created)
  returning id into v_app;

  insert into customer_profile (application_id, full_name, pan, aadhaar, mobile, dob, address, employer,
                                employment_status, monthly_salary_paise, salary_bank, email, official_email,
                                salary_account_number, salary_ifsc, bureau_score, bureau_source, risk_category,
                                pan_verified, aadhaar_linked, aadhaar_verified, email_verified, address_verified,
                                penny_drop_verified, created_at, created_by)
  values (v_app, 'Sim Trainee ' || lpad(p_n::text, 4, '0'), 'SIMXT' || lpad((p_n % 10000)::text, 4, '0') || 'Z',
          '0' || lpad(p_n::text, 11, '0'), v_mobile, date '1990-01-01', 'Training Address, Sample City',
          'Sample Employer Pvt Ltd', 'SALARIED', v_salary, 'Sample Bank',
          'trainee' || p_n || '@example.invalid', 'trainee' || p_n || '@example.invalid',
          '000000' || p_n, 'SIMB0000001', 720, 'SIMULATION', 'B',
          true, true, true, true, true, true, p_created, 'sim-seed');

  insert into borrower_mobile (customer_id, mobile, created_at) values (v_customer, v_mobile, p_created);

  foreach v_to in array v_path[1:v_last] loop
    -- A closed loan went through ACTIVE, not OVERDUE; only overdue files record that step.
    continue when v_to = 'OVERDUE' and p_status <> 'OVERDUE';
    insert into application_event (application_id, from_status, to_status, actor_id, actor_role, action, notes, at)
    values (v_app, v_prev, v_to, 'sim-seed', 'SYSTEM', case when v_prev is null then 'CREATE' else 'SIM_SEED' end,
            'Training data', p_created + make_interval(hours => v_step));
    v_prev := v_to;
    v_step := v_step + 1;
  end loop;
  return v_app;
end $f$;

create function pg_temp.sim_loan(p_n int, p_principal bigint, p_disbursed date, p_due date, p_status text)
returns bigint language plpgsql as $f$
declare
  v_fee bigint := round(p_principal * 0.10);
  v_gst bigint := round(v_fee * 0.18);
  v_days int := greatest(p_due - p_disbursed, 1);
  v_total bigint := p_principal + round(p_principal * 0.01 * v_days);
  v_closed boolean := p_status = 'CLOSED';
  v_paid date := least(p_due, current_date);
  v_loan bigint;
begin
  insert into loan (customer_id, principal, processing_fee, gst, net_disbursed, daily_interest_rate, disbursed_on,
                    due_date, total_repayable, outstanding, status, created_at, created_by, disbursal_txn_ref, closed_on)
  values (900000000 + p_n, p_principal, v_fee, v_gst, p_principal - v_fee - v_gst, 0.0100, p_disbursed, p_due, v_total,
          case when v_closed then 0 else v_total end, p_status, p_disbursed::timestamptz, 'sim-seed',
          'SIM-TXN-' || p_n, case when v_closed then v_paid end)
  returning id into v_loan;

  perform pg_temp.sim_application(p_n, p_status, p_principal, v_loan, (p_disbursed - 2)::timestamptz, p_due);

  if v_closed then
    insert into payment (loan_id, amount, method, status, txn_ref, paid_on, partial, created_at, created_by, decided_at)
    values (v_loan, v_total, 'UPI', 'VERIFIED', 'SIM-PAY-' || p_n, v_paid, false, v_paid::timestamptz, 'sim-seed',
            v_paid::timestamptz);
  end if;
  if p_status = 'OVERDUE' then
    insert into collection_case (id, loan_id, created_at) values (gen_random_uuid(), v_loan, (p_due + 1)::timestamptz);
  end if;
  return v_loan;
end $f$;

create function pg_temp.sim_principal(p_n int) returns bigint language sql as $f$
  select (array[200000, 300000, 400000, 500000, 600000, 700000, 800000]::bigint[])[(p_n % 7) + 1]
$f$;

do $$
declare
  c_target constant bigint := 700000000;   -- Rs 70,00,000
  c_average constant bigint := 500000;     -- mean of sim_principal
  c_closure constant numeric := 0.60;
  v_first_disbursed date;
  v_first_due date;
  v_real_total bigint;
  v_n int := 0;
  v_s text;
  v_i int;
  v_loan bigint;
  v_months date[];
  v_month date;
  v_count int;
  v_each int;
  v_extra int;
  v_quota int;
  v_real_due int;
  v_real_closed int;
  v_add_closed int;
  v_due date;
  v_total bigint;
  v_avg numeric;
begin
  select min(l.disbursed_on), min(l.due_date), coalesce(sum(l.principal), 0)
    into v_first_disbursed, v_first_due, v_real_total
    from loan l join loan_application a on a.loan_id = l.id
   where a.status <> 'DRAFT' and l.disbursed_on is not null;

  if v_first_disbursed is null then
    raise exception 'STOP: the copy has no disbursed loans to build on';
  end if;
  if v_real_total >= c_target then
    raise exception 'STOP: real book (% paise) already meets the target. Ask the owner.', v_real_total;
  end if;

  select array_agg(m::date order by m) into v_months
    from generate_series(date_trunc('month', v_first_due), date_trunc('month', current_date) - interval '1 month',
                         interval '1 month') m;
  if v_months is null then
    raise exception 'STOP: no completed months to spread training loans across';
  end if;

  -- Open files for every stage of the console.
  foreach v_s in array array['CREDIT_EXEC_PENDING', 'SANCTIONED', 'DISBURSEMENT_PENDING'] loop
    for v_i in 1..5 loop
      v_n := v_n + 1;
      perform pg_temp.sim_application(v_n, v_s, pg_temp.sim_principal(v_n), null, now() - interval '3 days',
                                      current_date + 25);
    end loop;
  end loop;
  for v_i in 1..5 loop
    v_n := v_n + 1;
    perform pg_temp.sim_loan(v_n, pg_temp.sim_principal(v_n), (current_date - 10)::date, (current_date + 20)::date, 'ACTIVE');
  end loop;
  for v_i in 1..5 loop
    v_n := v_n + 1;
    perform pg_temp.sim_loan(v_n, pg_temp.sim_principal(v_n), (current_date - 40)::date, (current_date - 5)::date, 'OVERDUE');
  end loop;
  for v_i in 1..5 loop
    v_n := v_n + 1;
    v_loan := pg_temp.sim_loan(v_n, pg_temp.sim_principal(v_n), (current_date - 15)::date, (current_date + 15)::date, 'ACTIVE');
    insert into payment (loan_id, amount, method, status, txn_ref, paid_on, partial, created_at, created_by)
    select id, total_repayable, 'UPI', 'PENDING_VERIFICATION', 'SIM-PAY-' || v_n, current_date, false, now(), 'sim-seed'
      from loan where id = v_loan;
  end loop;

  select sum(l.principal) into v_total
    from loan l join loan_application a on a.loan_id = l.id
   where a.status <> 'DRAFT' and l.disbursed_on is not null;

  v_count := round((c_target - v_total)::numeric / c_average);
  v_each := v_count / array_length(v_months, 1);
  v_extra := v_count % array_length(v_months, 1);

  foreach v_month in array v_months loop
    v_quota := v_each + case when v_extra > 0 then 1 else 0 end;
    v_extra := greatest(v_extra - 1, 0);

    select count(*), count(*) filter (where l.status = 'CLOSED') into v_real_due, v_real_closed
      from loan l join loan_application a on a.loan_id = l.id
     where a.status <> 'DRAFT' and l.disbursed_on is not null
       and date_trunc('month', l.due_date) = v_month;

    v_add_closed := greatest(0, least(v_quota, round(c_closure * (v_real_due + v_quota))::int - v_real_closed));

    for v_i in 1..v_quota loop
      v_n := v_n + 1;
      v_due := greatest(v_month + (v_n % 27), v_first_due);
      perform pg_temp.sim_loan(v_n, pg_temp.sim_principal(v_n), greatest(v_first_disbursed, v_due - (15 + v_n % 25)), v_due,
                               case when v_i <= v_add_closed then 'CLOSED' else 'OVERDUE' end);
    end loop;
  end loop;

  select sum(l.principal) into v_total
    from loan l join loan_application a on a.loan_id = l.id
   where a.status <> 'DRAFT' and l.disbursed_on is not null;

  select avg(closed::numeric / due) into v_avg from (
    select count(*) due, count(*) filter (where l.status = 'CLOSED') closed
      from loan l join loan_application a on a.loan_id = l.id
     where a.status <> 'DRAFT' and l.disbursed_on is not null
       and l.due_date < date_trunc('month', current_date)
     group by date_trunc('month', l.due_date)) m;

  if abs(v_total - c_target) > c_target / 100 or abs(v_avg - c_closure) > 0.02 then
    raise exception 'STOP: targets not reached (total % paise, average closure %). Nothing was saved. Ask the owner.',
      v_total, round(v_avg, 4);
  end if;

  raise notice 'Added % made-up files. Total % paise, average monthly closure %.', v_n, v_total, round(v_avg, 4);
end $$;

commit;
```

- [ ] **Step 6: Create the verify script**

Create `scripts/sim/30_verify.sql`:

```sql
-- Fails unless the copy is locked down and shows the training figures.
do $$
declare
  v_total bigint;
  v_avg numeric;
  v_active int;
begin
  if to_regclass('public.sim_marker') is null then
    raise exception 'REFUSED: not the simulation database (sim_marker missing)';
  end if;

  select count(*) into v_active from staff_user where status = 'ACTIVE';
  if v_active <> 1 then raise exception 'Expected one active staff user, found %', v_active; end if;

  if exists (select 1 from feature_flag where enabled and flag_key in ('digio-mandate', 'digio-debit', 'payu-repayment')) then
    raise exception 'A payment flag is still on';
  end if;

  select sum(l.principal) into v_total
    from loan l join loan_application a on a.loan_id = l.id
   where a.status <> 'DRAFT' and l.disbursed_on is not null;

  select avg(closed::numeric / due) into v_avg from (
    select count(*) due, count(*) filter (where l.status = 'CLOSED') closed
      from loan l join loan_application a on a.loan_id = l.id
     where a.status <> 'DRAFT' and l.disbursed_on is not null
       and l.due_date < date_trunc('month', current_date)
     group by date_trunc('month', l.due_date)) m;

  if abs(v_total - 700000000) > 7000000 then raise exception 'Total principal off target: %', v_total; end if;
  if abs(v_avg - 0.60) > 0.02 then raise exception 'Average closure off target: %', v_avg; end if;

  raise notice 'VERIFIED: total % paise, average monthly closure %, one active staff user', v_total, round(v_avg, 4);
end $$;
```

- [ ] **Step 7: Create the tools image definition**

Create `scripts/sim/Dockerfile`:

```dockerfile
# One-off runner for the simulation SQL, run as an ECS task inside the private network.
FROM postgres:18-alpine
COPY sim-run.sh 10_prepare.sql 20_training_loans.sql 30_verify.sql /sim/
# A Windows checkout can leave CRLF line endings, which break the shebang in Linux.
RUN sed -i 's/\r$//' /sim/sim-run.sh && chmod +x /sim/sim-run.sh
ENTRYPOINT ["/sim/sim-run.sh"]
```

- [ ] **Step 8: Run the local test to verify it passes**

Run: `chmod +x scripts/sim/sim-run.sh scripts/sim/test/run-local.sh; set -o pipefail; bash scripts/sim/test/run-local.sh; echo "exit=$?"`
Expected: every line is `PASS:`, then `ALL PASSED`, `exit=0`.

If a column name or constraint from migrations V67–V99 fails an insert, correct the insert to match the schema and re-run. Do not relax the assertions.

- [ ] **Step 9: Commit**

```bash
git add scripts/sim
git commit -m "feat(sim): add lockdown and training-data scripts with local tests" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Build the empty sim infrastructure

No production data moves in this task.

**Files:**
- Create: `scripts/sim/infra.sh`

**Interfaces:**
- Produces: `infra.sh base|secrets|images|edge|restore|copy-docs|tools <arg>|service|status|teardown`.
- Resource names: DB `navix-finance-sim`, bucket `navix-finance-sim-documents`, cluster `navix-sim`, service `navix-sim-backend`, ALB `navix-sim-alb`, ECR `navix-sim`, KMS `alias/navix-sim`.

- [ ] **Step 1: Write the script**

Create `scripts/sim/infra.sh`:

```bash
#!/usr/bin/env bash
# Builds and removes the isolated DhanBoost simulation environment.
# Every WRITE targets a resource with "sim" in its name. Production names appear only in READs.
set -euo pipefail
export MSYS_NO_PATHCONV=1 AWS_PROFILE=navix-dev AWS_REGION=ap-south-1 AWS_PAGER=

ACCOUNT=382188661325
VPC=vpc-0446be8c3d6a3941d
PRIVATE="subnet-04debb2364f89a58f subnet-0e88775cc900d5a94 subnet-0ec4c5696d5c2069f"
PUBLIC="subnet-0341c9ce4312c066d subnet-0dc2e83e75c4ac01e subnet-0415f0d0f4c913dae"
CF_PREFIX_LIST=pl-9aa247f3

# Production: read-only sources.
SOURCE_DB=navix-finance-dev
SOURCE_BUCKET=navix-finance-bucket
SOURCE_DOC_KEY=arn:aws:kms:ap-south-1:382188661325:key/ebef2503-c1d6-464c-b7b0-d3f49773b049

SIM_DB=navix-finance-sim
SIM_BUCKET=navix-finance-sim-documents
CLUSTER=navix-sim
SERVICE=navix-sim-backend
REPO=$ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com/navix-sim
# Windows-form path: MSYS_NO_PATHCONV stops Git Bash translating /c/... for docker.exe.
ROOT=$(cd "$(dirname "$0")/../.." && pwd -W)

sg() { aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$VPC" "Name=group-name,Values=$1" --query 'SecurityGroups[0].GroupId' --output text; }
csv() { echo "$1" | tr ' ' ','; }
key_arn() { aws kms describe-key --key-id alias/navix-sim --query KeyMetadata.Arn --output text; }
put() { aws ssm put-parameter --name "$1" --type "$2" --value "$3" --overwrite >/dev/null; }
get() { aws ssm get-parameter --name "$1" --with-decryption --query Parameter.Value --output text; }
db_host() { aws rds describe-db-instances --db-instance-identifier $SIM_DB --query 'DBInstances[0].Endpoint.Address' --output text; }
network() { echo "awsvpcConfiguration={subnets=[$(csv "$PRIVATE")],securityGroups=[$(sg navix-sim-task)],assignPublicIp=DISABLED}"; }
trust='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

base() {
  KEY=$(aws kms create-key --description "DhanBoost simulation environment" --query KeyMetadata.KeyId --output text)
  aws kms create-alias --alias-name alias/navix-sim --target-key-id "$KEY"

  aws s3api create-bucket --bucket $SIM_BUCKET --create-bucket-configuration LocationConstraint=$AWS_REGION >/dev/null
  aws s3api put-public-access-block --bucket $SIM_BUCKET --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  aws s3api put-bucket-encryption --bucket $SIM_BUCKET --server-side-encryption-configuration "{\"Rules\":[{\"ApplyServerSideEncryptionByDefault\":{\"SSEAlgorithm\":\"aws:kms\",\"KMSMasterKeyID\":\"$(key_arn)\"},\"BucketKeyEnabled\":true}]}"
  aws s3api put-bucket-cors --bucket $SIM_BUCKET --cors-configuration '{"CORSRules":[{"AllowedHeaders":["*"],"AllowedMethods":["PUT","GET","HEAD"],"AllowedOrigins":["https://dhanboost.com","https://app.dhanboost.com"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":3000}]}'

  aws ecr create-repository --repository-name navix-sim >/dev/null
  aws logs create-log-group --log-group-name /ecs/navix-sim
  aws ecs create-cluster --cluster-name $CLUSTER >/dev/null

  for n in navix-sim-alb navix-sim-task navix-sim-db; do
    aws ec2 create-security-group --group-name $n --description "$n" --vpc-id $VPC >/dev/null
  done
  aws ec2 authorize-security-group-ingress --group-id "$(sg navix-sim-alb)" --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,PrefixListIds=[{PrefixListId=$CF_PREFIX_LIST}]" >/dev/null
  aws ec2 authorize-security-group-ingress --group-id "$(sg navix-sim-task)" --protocol tcp --port 8080 --source-group "$(sg navix-sim-alb)" >/dev/null
  aws ec2 authorize-security-group-ingress --group-id "$(sg navix-sim-db)" --protocol tcp --port 5432 --source-group "$(sg navix-sim-task)" >/dev/null
  aws rds create-db-subnet-group --db-subnet-group-name navix-sim --db-subnet-group-description "Simulation private subnets" --subnet-ids $PRIVATE >/dev/null

  local key; key=$(key_arn)
  aws iam create-role --role-name navix-sim-exec-role --assume-role-policy-document "$trust" >/dev/null
  aws iam attach-role-policy --role-name navix-sim-exec-role --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
  aws iam put-role-policy --role-name navix-sim-exec-role --policy-name sim-secrets --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ssm:GetParameters\",\"Resource\":[\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix-sim-tools/*\",\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix/sim/spring/datasource/password\"]}]}"

  aws iam create-role --role-name navix-sim-task-role --assume-role-policy-document "$trust" >/dev/null
  aws iam put-role-policy --role-name navix-sim-task-role --policy-name sim-only --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"ssm:GetParameter\",\"ssm:GetParameters\",\"ssm:GetParametersByPath\"],\"Resource\":\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix/sim*\"},{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"arn:aws:s3:::$SIM_BUCKET\"},{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:PutObject\",\"s3:DeleteObject\"],\"Resource\":\"arn:aws:s3:::$SIM_BUCKET/*\"},{\"Effect\":\"Allow\",\"Action\":[\"kms:Decrypt\",\"kms:GenerateDataKey\"],\"Resource\":\"$key\"}]}"

  # The ONLY identity with access to production documents: three read actions there, writes only to the sim bucket.
  aws iam create-role --role-name navix-sim-copy-role --assume-role-policy-document "$trust" >/dev/null
  aws iam put-role-policy --role-name navix-sim-copy-role --policy-name read-source-write-sim --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":[\"arn:aws:s3:::$SOURCE_BUCKET\",\"arn:aws:s3:::$SIM_BUCKET\"]},{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::$SOURCE_BUCKET/*\"},{\"Effect\":\"Allow\",\"Action\":\"kms:Decrypt\",\"Resource\":\"$SOURCE_DOC_KEY\"},{\"Effect\":\"Allow\",\"Action\":\"s3:PutObject\",\"Resource\":\"arn:aws:s3:::$SIM_BUCKET/*\"},{\"Effect\":\"Allow\",\"Action\":[\"kms:GenerateDataKey\",\"kms:Decrypt\"],\"Resource\":\"$key\"}]}"
}

secrets() {
  put /navix/sim/navix/auth/secret SecureString "$(openssl rand -base64 48)"
  put /navix/sim/spring/datasource/username String navix_app
  put /navix/sim/spring/datasource/password SecureString "$(openssl rand -hex 24)"
  put /navix/sim/navix/storage/bucket String $SIM_BUCKET
  put /navix/sim/navix/storage/kms-key-id String "$(key_arn)"
  put /navix/sim/navix/app/frontend-base-url String https://dhanboost.com
  put /navix-sim-tools/db-master-password SecureString "$(openssl rand -hex 24)"
  put /navix-sim-tools/admin-password SecureString "$(openssl rand -hex 12)"
  put /navix-sim-tools/gateway-key SecureString "$(openssl rand -hex 32)"
}

images() {
  aws ecr get-login-password | docker login --username AWS --password-stdin "$ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com"
  (cd "$ROOT/backend" && ./mvnw -q -pl navix-app -am package -DskipTests)
  local tag; tag=$(git -C "$ROOT" rev-parse --short HEAD)
  docker build -f "$ROOT/Dockerfile.backend.runtime" -t "$REPO:backend-$tag" -t "$REPO:backend" "$ROOT"
  docker build -t "$REPO:tools" "$ROOT/scripts/sim"
  docker push "$REPO:backend-$tag"; docker push "$REPO:backend"; docker push "$REPO:tools"
}

edge() {
  local alb tg listener dns key
  alb=$(aws elbv2 create-load-balancer --name navix-sim-alb --type application --scheme internet-facing --subnets $PUBLIC --security-groups "$(sg navix-sim-alb)" --query 'LoadBalancers[0].LoadBalancerArn' --output text)
  tg=$(aws elbv2 create-target-group --name navix-sim-tg --protocol HTTP --port 8080 --vpc-id $VPC --target-type ip --health-check-path /actuator/health --query 'TargetGroups[0].TargetGroupArn' --output text)
  listener=$(aws elbv2 create-listener --load-balancer-arn "$alb" --protocol HTTP --port 80 --default-actions 'Type=fixed-response,FixedResponseConfig={StatusCode=403,ContentType=text/plain,MessageBody=Forbidden}' --query 'Listeners[0].ListenerArn' --output text)
  key=$(get /navix-sim-tools/gateway-key)
  aws elbv2 create-rule --listener-arn "$listener" --priority 1 --conditions '[{"Field":"path-pattern","PathPatternConfig":{"Values":["/api/auth/borrower/*"]}}]' --actions 'Type=fixed-response,FixedResponseConfig={StatusCode=403,ContentType=text/plain,MessageBody=Forbidden}' >/dev/null
  aws elbv2 create-rule --listener-arn "$listener" --priority 2 --conditions "[{\"Field\":\"http-header\",\"HttpHeaderConfig\":{\"HttpHeaderName\":\"X-Sim-Gateway\",\"Values\":[\"$key\"]}}]" --actions "Type=forward,TargetGroupArn=$tg" >/dev/null
  dns=$(aws elbv2 describe-load-balancers --load-balancer-arns "$alb" --query 'LoadBalancers[0].DNSName' --output text)
  aws cloudfront create-distribution --distribution-config "{\"CallerReference\":\"navix-sim-$(date +%s)\",\"Comment\":\"navix-sim\",\"Enabled\":true,\"PriceClass\":\"PriceClass_200\",\"Origins\":{\"Quantity\":1,\"Items\":[{\"Id\":\"navix-sim-alb\",\"DomainName\":\"$dns\",\"CustomOriginConfig\":{\"HTTPPort\":80,\"HTTPSPort\":443,\"OriginProtocolPolicy\":\"http-only\",\"OriginReadTimeout\":60}}]},\"DefaultCacheBehavior\":{\"TargetOriginId\":\"navix-sim-alb\",\"ViewerProtocolPolicy\":\"https-only\",\"CachePolicyId\":\"4135ea2d-6df8-44a3-9df3-4b5a84be39ad\",\"OriginRequestPolicyId\":\"216adef6-5c7f-47e4-b989-5492eafa07d3\",\"AllowedMethods\":{\"Quantity\":7,\"Items\":[\"GET\",\"HEAD\",\"OPTIONS\",\"PUT\",\"PATCH\",\"POST\",\"DELETE\"],\"CachedMethods\":{\"Quantity\":2,\"Items\":[\"GET\",\"HEAD\"]}}}}" --query 'Distribution.DomainName' --output text
}

require_signoff() {
  [ "${COMPLIANCE_SIGNOFF_CONFIRMED_BY_OWNER:-}" = yes ] || { echo "REFUSED: owner has not confirmed compliance sign-off" >&2; exit 3; }
}

restore() {
  require_signoff
  local src copy
  src=$(aws rds describe-db-snapshots --db-instance-identifier $SOURCE_DB --snapshot-type automated --query 'reverse(sort_by(DBSnapshots,&SnapshotCreateTime))[0].DBSnapshotIdentifier' --output text)
  [ "$src" != None ] || { echo "STOP: production has no automated backup. Ask the owner." >&2; exit 4; }
  copy="navix-sim-seed-$(date +%Y%m%d%H%M)"
  aws rds copy-db-snapshot --source-db-snapshot-identifier "$src" --target-db-snapshot-identifier "$copy" --kms-key-id alias/navix-sim >/dev/null
  aws rds wait db-snapshot-available --db-snapshot-identifier "$copy"
  aws rds restore-db-instance-from-db-snapshot --db-instance-identifier $SIM_DB --db-snapshot-identifier "$copy" --db-instance-class db.t4g.micro --db-subnet-group-name navix-sim --vpc-security-group-ids "$(sg navix-sim-db)" --no-publicly-accessible --no-multi-az >/dev/null
  aws rds wait db-instance-available --db-instance-identifier $SIM_DB
  # A restored instance keeps production's master password until this runs.
  aws rds modify-db-instance --db-instance-identifier $SIM_DB --master-user-password "$(get /navix-sim-tools/db-master-password)" --backup-retention-period 1 --apply-immediately >/dev/null
  aws rds wait db-instance-available --db-instance-identifier $SIM_DB
  put /navix/sim/spring/datasource/url String "jdbc:postgresql://$(db_host):5432/navix?sslmode=require"
  aws rds delete-db-snapshot --db-snapshot-identifier "$copy" >/dev/null
  register_tools
}

register_tools() {
  aws ecs register-task-definition --cli-input-json "{\"family\":\"navix-sim-tools\",\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\",\"cpu\":\"256\",\"memory\":\"512\",\"executionRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-exec-role\",\"containerDefinitions\":[{\"name\":\"tools\",\"image\":\"$REPO:tools\",\"environment\":[{\"name\":\"PGHOST\",\"value\":\"$(db_host)\"},{\"name\":\"PGDATABASE\",\"value\":\"navix\"},{\"name\":\"PGUSER\",\"value\":\"navix_admin\"},{\"name\":\"PGSSLMODE\",\"value\":\"require\"}],\"secrets\":[{\"name\":\"PGPASSWORD\",\"valueFrom\":\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix-sim-tools/db-master-password\"},{\"name\":\"SIM_ADMIN_PASSWORD\",\"valueFrom\":\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix-sim-tools/admin-password\"},{\"name\":\"SIM_APP_DB_PASSWORD\",\"valueFrom\":\"arn:aws:ssm:$AWS_REGION:$ACCOUNT:parameter/navix/sim/spring/datasource/password\"}],\"logConfiguration\":{\"logDriver\":\"awslogs\",\"options\":{\"awslogs-group\":\"/ecs/navix-sim\",\"awslogs-region\":\"$AWS_REGION\",\"awslogs-stream-prefix\":\"tools\"}}}]}" >/dev/null
}

run_and_wait() { # args: task-definition, run-task --overrides JSON or empty
  local arn code
  if [ -n "$2" ]; then
    arn=$(aws ecs run-task --cluster $CLUSTER --launch-type FARGATE --task-definition "$1" --network-configuration "$(network)" --overrides "$2" --query 'tasks[0].taskArn' --output text)
  else
    arn=$(aws ecs run-task --cluster $CLUSTER --launch-type FARGATE --task-definition "$1" --network-configuration "$(network)" --query 'tasks[0].taskArn' --output text)
  fi
  echo "task: $arn"
  until aws ecs describe-tasks --cluster $CLUSTER --tasks "$arn" --query 'tasks[0].lastStatus' --output text | grep -q STOPPED; do sleep 15; done
  code=$(aws ecs describe-tasks --cluster $CLUSTER --tasks "$arn" --query 'tasks[0].containers[0].exitCode' --output text)
  echo "exit code: $code"
  [ "$code" = 0 ]
}

copy_docs() {
  require_signoff
  aws ecs register-task-definition --cli-input-json "{\"family\":\"navix-sim-copy\",\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\",\"cpu\":\"512\",\"memory\":\"1024\",\"executionRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-exec-role\",\"taskRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-copy-role\",\"containerDefinitions\":[{\"name\":\"copy\",\"image\":\"public.ecr.aws/aws-cli/aws-cli:latest\",\"command\":[\"s3\",\"sync\",\"s3://$SOURCE_BUCKET\",\"s3://$SIM_BUCKET\",\"--sse\",\"aws:kms\",\"--sse-kms-key-id\",\"$(key_arn)\",\"--copy-props\",\"none\",\"--only-show-errors\"],\"logConfiguration\":{\"logDriver\":\"awslogs\",\"options\":{\"awslogs-group\":\"/ecs/navix-sim\",\"awslogs-region\":\"$AWS_REGION\",\"awslogs-stream-prefix\":\"copy\"}}}]}" >/dev/null
  run_and_wait navix-sim-copy ""
}

tools() { # args: prepare | loans | verify
  run_and_wait navix-sim-tools "{\"containerOverrides\":[{\"name\":\"tools\",\"command\":[\"$1\"]}]}"
}

service() {
  local e=""
  for kv in NAVIX_ENV=sim AWS_REGION=$AWS_REGION NAVIX_ALLOW_DEV_DEFAULTS=true NAVIX_SMS_ENABLED=false NAVIX_SMS_MOCK=false NAVIX_SMS_DEV_ECHO=false NAVIX_WHATSAPP_ENABLED=false NAVIX_EMAIL_ENABLED=false NAVIX_EMAIL_PROVIDER=log NAVIX_SES_EVENTS_ENABLED=false NAVIX_ESIGN_PROVIDER=mock NAVIX_DIGIO_PROVIDER=mock NAVIX_BUREAU_FIXTURE=1 NAVIX_REMINDERS_CRON=- NAVIX_EMPLOYMENT_RETRY_CRON=- NAVIX_PROVIDER_HEALTH_CRON=- NAVIX_DIGIO_DEBIT_CRON=- NAVIX_PAYU_RECONCILE_CRON=-; do
    e="$e{\"name\":\"${kv%%=*}\",\"value\":\"${kv#*=}\"},"
  done
  aws ecs register-task-definition --cli-input-json "{\"family\":\"navix-sim-backend\",\"requiresCompatibilities\":[\"FARGATE\"],\"networkMode\":\"awsvpc\",\"cpu\":\"1024\",\"memory\":\"2048\",\"executionRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-exec-role\",\"taskRoleArn\":\"arn:aws:iam::$ACCOUNT:role/navix-sim-task-role\",\"containerDefinitions\":[{\"name\":\"backend\",\"image\":\"$REPO:backend\",\"portMappings\":[{\"containerPort\":8080}],\"environment\":[${e%,}],\"logConfiguration\":{\"logDriver\":\"awslogs\",\"options\":{\"awslogs-group\":\"/ecs/navix-sim\",\"awslogs-region\":\"$AWS_REGION\",\"awslogs-stream-prefix\":\"backend\"}}}]}" >/dev/null
  local tg; tg=$(aws elbv2 describe-target-groups --names navix-sim-tg --query 'TargetGroups[0].TargetGroupArn' --output text)
  aws ecs create-service --cluster $CLUSTER --service-name $SERVICE --task-definition navix-sim-backend --desired-count 1 --launch-type FARGATE --health-check-grace-period-seconds 240 --network-configuration "$(network)" --load-balancers "targetGroupArn=$tg,containerName=backend,containerPort=8080" >/dev/null
  aws ecs wait services-stable --cluster $CLUSTER --services $SERVICE
}

status() {
  aws ecs describe-services --cluster $CLUSTER --services $SERVICE --query 'services[0].{desired:desiredCount,running:runningCount}' --output json
  aws cloudfront list-distributions --query "DistributionList.Items[?Comment=='navix-sim'].{domain:DomainName,status:Status}" --output json
  aws rds describe-db-instances --db-instance-identifier $SIM_DB --query 'DBInstances[0].{status:DBInstanceStatus,public:PubliclyAccessible,encrypted:StorageEncrypted}' --output json
}

teardown() {
  [ "${CONFIRM_TEARDOWN:-}" = navix-sim ] || { echo "Set CONFIRM_TEARDOWN=navix-sim" >&2; exit 5; }
  aws ecs update-service --cluster $CLUSTER --service $SERVICE --desired-count 0 >/dev/null || true
  aws ecs delete-service --cluster $CLUSTER --service $SERVICE --force >/dev/null || true
  aws rds delete-db-instance --db-instance-identifier $SIM_DB --skip-final-snapshot --delete-automated-backups >/dev/null || true
  aws rds wait db-instance-deleted --db-instance-identifier $SIM_DB || true
  aws s3 rm "s3://$SIM_BUCKET" --recursive --only-show-errors || true
  aws s3api delete-bucket --bucket $SIM_BUCKET || true
  aws ssm delete-parameters --names $(aws ssm describe-parameters --parameter-filters "Key=Path,Option=Recursive,Values=/navix/sim/" --query 'Parameters[].Name' --output text) || true
  aws ssm delete-parameters --names $(aws ssm describe-parameters --parameter-filters "Key=Path,Option=Recursive,Values=/navix-sim-tools/" --query 'Parameters[].Name' --output text) || true

  local id etag cfg
  id=$(aws cloudfront list-distributions --query "DistributionList.Items[?Comment=='navix-sim'].Id | [0]" --output text)
  if [ "$id" != None ]; then
    etag=$(aws cloudfront get-distribution-config --id "$id" --query ETag --output text)
    cfg=$(aws cloudfront get-distribution-config --id "$id" --query DistributionConfig --output json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const c=JSON.parse(s);c.Enabled=false;console.log(JSON.stringify(c))})')
    aws cloudfront update-distribution --id "$id" --if-match "$etag" --distribution-config "$cfg" >/dev/null
    aws cloudfront wait distribution-deployed --id "$id"
    aws cloudfront delete-distribution --id "$id" --if-match "$(aws cloudfront get-distribution-config --id "$id" --query ETag --output text)"
  fi

  local alb; alb=$(aws elbv2 describe-load-balancers --names navix-sim-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text)
  aws elbv2 delete-load-balancer --load-balancer-arn "$alb"
  aws elbv2 wait load-balancers-deleted --load-balancer-arns "$alb"
  aws elbv2 delete-target-group --target-group-arn "$(aws elbv2 describe-target-groups --names navix-sim-tg --query 'TargetGroups[0].TargetGroupArn' --output text)"
  aws ecs delete-cluster --cluster $CLUSTER >/dev/null
  aws rds delete-db-subnet-group --db-subnet-group-name navix-sim
  sleep 120 # task network interfaces release after the service stops
  aws ec2 revoke-security-group-ingress --group-id "$(sg navix-sim-db)" --protocol tcp --port 5432 --source-group "$(sg navix-sim-task)" || true
  aws ec2 revoke-security-group-ingress --group-id "$(sg navix-sim-task)" --protocol tcp --port 8080 --source-group "$(sg navix-sim-alb)" || true
  for n in navix-sim-db navix-sim-task navix-sim-alb; do aws ec2 delete-security-group --group-id "$(sg $n)"; done
  aws iam delete-role-policy --role-name navix-sim-exec-role --policy-name sim-secrets
  aws iam detach-role-policy --role-name navix-sim-exec-role --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
  aws iam delete-role --role-name navix-sim-exec-role
  aws iam delete-role-policy --role-name navix-sim-task-role --policy-name sim-only
  aws iam delete-role --role-name navix-sim-task-role
  aws iam delete-role-policy --role-name navix-sim-copy-role --policy-name read-source-write-sim
  aws iam delete-role --role-name navix-sim-copy-role
  aws ecr delete-repository --repository-name navix-sim --force >/dev/null
  aws logs delete-log-group --log-group-name /ecs/navix-sim
  local key; key=$(key_arn)
  aws kms delete-alias --alias-name alias/navix-sim
  aws kms schedule-key-deletion --key-id "$key" --pending-window-in-days 7 >/dev/null
}

case "${1:-}" in
  base|secrets|images|edge|restore|service|status|teardown) "$1" ;;
  copy-docs) copy_docs ;;
  tools) tools "$2" ;;
  *) echo "usage: infra.sh base|secrets|images|edge|restore|copy-docs|tools <prepare|loans|verify>|service|status|teardown" >&2; exit 64 ;;
esac
```

- [ ] **Step 2: Audit that production appears only in read paths**

```bash
cd /c/Users/gshub/Navix-Finance-sim
grep -n -E 'navix-backend|navix-alb\b|navix-cluster|navix-finance-task-role|/navix/dev' scripts/sim/infra.sh; echo "forbidden_grep=$?"
grep -n -E '\$SOURCE_DB|\$SOURCE_BUCKET|\$SOURCE_DOC_KEY' scripts/sim/infra.sh
bash -n scripts/sim/infra.sh; echo "syntax=$?"
```

Expected: `forbidden_grep=1` (no matches), `syntax=0`. Every `SOURCE_*` line is one of: the assignments, `describe-db-snapshots`, the copy role's read statements, or the `s3 sync` source.

- [ ] **Step 3: Create the base resources, secrets and images**

Requires a live `aws login --profile navix-dev`.

```bash
bash scripts/sim/infra.sh base; echo "base=$?"
bash scripts/sim/infra.sh secrets; echo "secrets=$?"
bash scripts/sim/infra.sh images; echo "images=$?"
```

Expected: all print 0.

- [ ] **Step 4: Create the load balancer and CloudFront address**

```bash
bash scripts/sim/infra.sh edge; echo "edge=$?"
```

Expected: prints a `*.cloudfront.net` domain, then `edge=0`. Record that domain for Task 11.

- [ ] **Step 5: Confirm production is unchanged**

```bash
aws ecs describe-services --profile navix-dev --region ap-south-1 --cluster navix-cluster --services navix-backend --query 'services[0].{td:taskDefinition,running:runningCount}' --output json
curl -sS -o /dev/null -w '%{http_code}\n' https://dhanboost.com/staff/login
```

Expected: task definition is still `navix-finance:231` unless someone else deployed since, `running` is 1, HTTP 200.

- [ ] **Step 6: Commit**

```bash
git add scripts/sim/infra.sh
git commit -m "feat(sim): add the isolated simulation infrastructure script" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: GATE — copy the data

- [ ] **Step 1: STOP and ask the owner**

Ask: "Do you confirm compliance sign-off for copying real Aadhaar, PAN, bureau data and customer documents into the training environment?" Do not continue without an explicit yes in the conversation. Record the date of the answer in the Task 13 runbook.

- [ ] **Step 2: Restore the database from a copied backup**

```bash
COMPLIANCE_SIGNOFF_CONFIRMED_BY_OWNER=yes bash scripts/sim/infra.sh restore; echo "restore=$?"
bash scripts/sim/infra.sh status 2>&1 | tail -6
```

Expected: `restore=0`, and the RDS block shows `"public": false`, `"encrypted": true`, `"status": "available"`. This takes 20–40 minutes, so run it in the background.

- [ ] **Step 3: Lock the copy down before anything can use it**

```bash
bash scripts/sim/infra.sh tools prepare; echo "prepare=$?"
```

Expected: `exit code: 0`, `prepare=0`.

- [ ] **Step 4: Copy the documents with the read-only role**

```bash
COMPLIANCE_SIGNOFF_CONFIRMED_BY_OWNER=yes bash scripts/sim/infra.sh copy-docs; echo "copy=$?"
aws s3 ls s3://navix-finance-sim-documents --recursive --summarize --profile navix-dev | tail -2
```

Expected: `copy=0`, and a non-zero object count.

- [ ] **Step 5: Confirm the production bucket and database were not modified**

```bash
aws rds describe-db-instances --profile navix-dev --region ap-south-1 --db-instance-identifier navix-finance-dev --query 'DBInstances[0].{status:DBInstanceStatus,modified:PendingModifiedValues}' --output json
```

Expected: `"status": "available"` and an empty `modified` object.

---

### Task 10: Start the sim backend and add training data

- [ ] **Step 1: Start the service**

```bash
bash scripts/sim/infra.sh service; echo "service=$?"
aws logs tail /ecs/navix-sim --profile navix-dev --region ap-south-1 --since 15m --log-stream-name-prefix backend --format short | grep -E 'Started|Successfully applied|Schema .* is up to date|ERROR' | tail -20
```

Expected: `service=0` and a Spring `Started` line. If the task keeps restarting, read the startup exception and fix the setting. Do not add vendor credentials to work around it.

- [ ] **Step 2: Add the training loans**

```bash
bash scripts/sim/infra.sh tools loans; echo "loans=$?"
aws logs tail /ecs/navix-sim --profile navix-dev --region ap-south-1 --since 5m --log-stream-name-prefix tools --format short | tail -5
```

Expected: `loans=0` and a `Added N made-up files` notice.

If it reports `STOP`, nothing was saved. Report the message to the owner and wait for a decision.

- [ ] **Step 3: Verify**

```bash
bash scripts/sim/infra.sh tools verify; echo "verify=$?"
```

Expected: `verify=0`, with a `VERIFIED:` notice in the logs.

---

### Task 11: Test the sim before any release

**Files:** none

- [ ] **Step 1: Load the test values without printing secrets**

```bash
export MSYS_NO_PATHCONV=1 AWS_PROFILE=navix-dev AWS_REGION=ap-south-1
CF=https://$(aws cloudfront list-distributions --query "DistributionList.Items[?Comment=='navix-sim'].DomainName | [0]" --output text)
KEY=$(aws ssm get-parameter --name /navix-sim-tools/gateway-key --with-decryption --query Parameter.Value --output text)
PW=$(aws ssm get-parameter --name /navix-sim-tools/admin-password --with-decryption --query Parameter.Value --output text)
ALB=$(aws elbv2 describe-load-balancers --names navix-sim-alb --query 'LoadBalancers[0].DNSName' --output text)
```

- [ ] **Step 2: Check the access controls**

```bash
curl -sS -o /dev/null -w 'no header: %{http_code}\n' "$CF/actuator/health"
curl -sS -o /dev/null -w 'with header: %{http_code}\n' -H "X-Sim-Gateway: $KEY" "$CF/actuator/health"
curl -sS -o /dev/null -w 'borrower auth: %{http_code}\n' -X POST -H "X-Sim-Gateway: $KEY" -H 'Content-Type: application/json' -d '{}' "$CF/api/auth/borrower/login"
curl -sS -m 10 -o /dev/null -w 'direct to ALB: %{http_code}\n' "http://$ALB/actuator/health" || echo "direct to ALB: refused"
```

Expected: `no header: 403`, `with header: 200`, `borrower auth: 403`, and `direct to ALB: 000` or `refused`.

- [ ] **Step 3: Check the login and token separation**

```bash
TOK=$(curl -sS -X POST -H "X-Sim-Gateway: $KEY" -H 'Content-Type: application/json' -d "{\"email\":\"sim.admin@dhanboost.com\",\"password\":\"$PW\"}" "$CF/api/auth/staff/login" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).data.token))')
curl -sS -o /dev/null -w 'token on sim: %{http_code}\n' -H "X-Sim-Gateway: $KEY" -H "Authorization: Bearer $TOK" "$CF/api/feature-flags"
curl -sS -o /dev/null -w 'token on production: %{http_code}\n' -H "Authorization: Bearer $TOK" "http://navix-alb-148184383.ap-south-1.elb.amazonaws.com/api/feature-flags"
```

Expected: `token on sim: 200`, `token on production: 401`.

- [ ] **Step 4: Run the real website against the sim locally**

In `C:/Users/gshub/Navix-Finance-sim/frontend`, create `.env.local` (gitignored) using the values from Step 1:

```
BACKEND_BASE_URL=http://localhost:9
SIM_LOGIN_EMAIL=sim.admin@dhanboost.com
SIM_BACKEND_BASE_URL=<value of $CF>
SIM_GATEWAY_KEY=<value of $KEY>
```

`http://localhost:9` makes any accidental production-path call fail instead of reaching a real backend.

Run `npm run dev`, then in a browser at `http://localhost:3000/staff/login` sign in as `sim.admin@dhanboost.com` and confirm:

- The red SIMULATION strip shows on every staff page.
- Staff dashboard, Business position, all-time period: Principal disbursed is about ₹70,00,000.
- Drill into that card: the record total matches.
- A CSV and a PDF export are labelled.
- A made-up file walks through: assign in Credit Head, sanction as Credit Executive, force to disbursement as Admin, release with a test transaction id, verify a repayment as Accountant, and the loan closes.
- A credit brief or sanction letter PDF shows the label.

If a made-up file cannot move through a stage because a required record is missing, add that record to `pg_temp.sim_application` in `20_training_loans.sql`, re-run Task 7's test, and restore again.

- [ ] **Step 5: Confirm nothing left the sim**

```bash
aws logs tail /ecs/navix-sim --profile navix-dev --region ap-south-1 --since 2h --log-stream-name-prefix backend --format short | grep -i -E 'smartchat|ultron|resend|ses.*send|signzy.*initiate|fintrix|digitap|payu|digio' | grep -v -i -E 'disabled|skip|mock|not configured' | tail -20; echo "rc=${PIPESTATUS[3]}"
```

Expected: no lines that show a request sent to a vendor. Investigate anything that appears before continuing.

- [ ] **Step 6: Remove the local secrets file**

```bash
rm frontend/.env.local
```

---

### Task 12: GATE — release

**Do Task 13 first**, so the runbook ships in this one push. Every push to `main` redeploys production.

- [ ] **Step 1: STOP and ask the owner**

Ask: "The sim passed its tests. Releasing means pushing to `main`, and CI will redeploy the website and backend. There are no new migrations, and only the sim account is affected. OK to release?" Do not continue without an explicit yes.

- [ ] **Step 2: Add the website settings**

Requires a Vercel login for the project serving `dhanboost.com` (`prj_ukQ9y9Qv4shSLIs0DQ4ahL5SP7WF`). These do nothing until the code ships. Run Task 11 Step 1 first in the same shell, so `$CF` and `$KEY` are set.

```bash
cd /c/Users/gshub/Navix-Finance-sim/frontend
export VERCEL_ORG_ID=team_fD7U271h9m7wnsgnUs5SuKOG VERCEL_PROJECT_ID=prj_ukQ9y9Qv4shSLIs0DQ4ahL5SP7WF
printf %s 'sim.admin@dhanboost.com' | npx vercel@latest env add SIM_LOGIN_EMAIL production
printf %s "$CF" | npx vercel@latest env add SIM_BACKEND_BASE_URL production
printf %s "$KEY" | npx vercel@latest env add SIM_GATEWAY_KEY production
npx vercel@latest env ls production | grep SIM_
```

Expected: three `SIM_` entries.

- [ ] **Step 3: Rebase on the latest live code and re-run all checks**

```bash
cd /c/Users/gshub/Navix-Finance-sim
git fetch origin main && git rebase origin/main
git diff --name-only origin/main HEAD | grep -c 'db/migration'
cd frontend && npx tsc --noEmit; echo "tsc=$?"; npx eslint .; echo "eslint=$?"; npx vitest run; echo "vitest=$?"
cd ../backend && ./mvnw -B test; echo "backend=$?"
cd .. && code-review-graph detect-changes --brief; echo "crg=$?"
```

Expected: the `db/migration` count is `0`, and every status prints 0.

- [ ] **Step 4: Confirm the active GitHub account and push**

```bash
gh auth status
git push origin sim/training-environment:main; echo "push=$?"
```

Expected: `push=0`. If the push is rejected as non-fast-forward, return to Step 3.

- [ ] **Step 5: Watch CI and verify what is actually running**

```bash
SHA=$(git rev-parse HEAD)
gh run list --branch main --limit 1
gh run watch "$(gh run list --branch main --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status; echo "ci=$?"
aws ecs describe-task-definition --profile navix-dev --region ap-south-1 --task-definition "$(aws ecs describe-services --profile navix-dev --region ap-south-1 --cluster navix-cluster --services navix-backend --query 'services[0].taskDefinition' --output text)" --query 'taskDefinition.containerDefinitions[0].image' --output text
echo "$SHA"
```

Expected: `ci=0`, and the running image tag equals `$SHA`.

- [ ] **Step 6: Run the post-release checks**

On `https://dhanboost.com/staff/login`:

- A normal staff account signs in and shows no SIMULATION strip.
- A normal borrower login works at `/login`.
- `sim.admin@dhanboost.com` signs in, shows the strip, and the dashboard shows about ₹70 lakh.
- In the sim, change one made-up customer detail. In a normal staff session, confirm that customer does not exist.

If any production check fails, redeploy the previous website version (`npx vercel@latest rollback`) and report to the owner.

---

### Task 13: Runbook and documentation

**Files:**
- Create: `scripts/sim/README.md`
- Modify: `aws.md` (new section at the end)

- [ ] **Step 1: Write the runbook**

Create `scripts/sim/README.md`:

```markdown
# DhanBoost simulation environment

A separate training copy of the business, entered by signing in as `sim.admin@dhanboost.com` on the
normal staff login. Nothing in it can reach the production database. Design:
`docs/superpowers/specs/2026-11-09-simulation-admin-environment-design.md`.

**It holds real customer identity data. Delete it when training ends.**

Compliance sign-off confirmed by the owner on: 2026-MM-DD

## Get the password

    aws ssm get-parameter --profile navix-dev --region ap-south-1 --name /navix-sim-tools/admin-password --with-decryption --query Parameter.Value --output text

## Reset after a training session

Figures drift as trainees disburse and close cases. To reset, delete the instance and restore again:

    aws rds delete-db-instance --profile navix-dev --region ap-south-1 --db-instance-identifier navix-finance-sim --skip-final-snapshot --delete-automated-backups
    aws rds wait db-instance-deleted --profile navix-dev --region ap-south-1 --db-instance-identifier navix-finance-sim
    COMPLIANCE_SIGNOFF_CONFIRMED_BY_OWNER=yes bash scripts/sim/infra.sh restore
    bash scripts/sim/infra.sh tools prepare
    aws ecs update-service --profile navix-dev --region ap-south-1 --cluster navix-sim --service navix-sim-backend --force-new-deployment
    aws ecs wait services-stable --profile navix-dev --region ap-south-1 --cluster navix-sim --services navix-sim-backend
    bash scripts/sim/infra.sh tools loans
    bash scripts/sim/infra.sh tools verify

## Pause between sessions

    aws ecs update-service --profile navix-dev --region ap-south-1 --cluster navix-sim --service navix-sim-backend --desired-count 0
    aws rds stop-db-instance --profile navix-dev --region ap-south-1 --db-instance-identifier navix-finance-sim

AWS restarts a stopped database after 7 days. To resume, start the database, then set desired count to 1.

## Delete everything

    CONFIRM_TEARDOWN=navix-sim bash scripts/sim/infra.sh teardown

Then remove `SIM_LOGIN_EMAIL`, `SIM_BACKEND_BASE_URL` and `SIM_GATEWAY_KEY` from the Vercel project and
redeploy. With those unset the sim route is inert.

## Limits

- One account allows one active session, so one person is signed in at a time.
- Admin corrections gated by an OTP are not expected to work, because no messages can be sent.
```

Replace `2026-MM-DD` with the date the owner confirmed sign-off in Task 9.

- [ ] **Step 2: Add a pointer to `aws.md`**

Append to `aws.md`:

```markdown

---

## Simulation environment

A separate training stack, fully isolated from the resources above: RDS `navix-finance-sim`, S3
`navix-finance-sim-documents`, ECS cluster `navix-sim`, ALB `navix-sim-alb` behind CloudFront, SSM
`/navix/sim/*`. It holds a copy of real customer data. Runbook: `scripts/sim/README.md`.
```

- [ ] **Step 3: Commit locally**

```bash
git add scripts/sim/README.md aws.md
git commit -m "docs(sim): add the simulation environment runbook" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

This commit ships with the Task 12 push. Do not push it separately.
