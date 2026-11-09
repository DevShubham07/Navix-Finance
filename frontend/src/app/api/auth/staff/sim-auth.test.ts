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

function stubFetch(body: string) {
  const f = vi.fn(async (..._args: Parameters<typeof fetch>) => new Response(body, { status: 200 }));
  vi.stubGlobal("fetch", f);
  return f;
}

const stubLoginResponse = () =>
  stubFetch(JSON.stringify({ data: { token: "jwt", id: 1, name: "A", role: "ADMIN", customerId: null } }));

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
    expect((f.mock.calls[0][1] as RequestInit).headers).not.toHaveProperty("X-Sim-Gateway");
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
    const f = stubFetch("{}");
    await logout();
    expect(String(f.mock.calls[0][0])).toBe(`${SIM}/api/auth/staff/logout`);
    expect((f.mock.calls[0][1] as RequestInit).headers).toMatchObject({ "X-Sim-Gateway": "gateway-key" });
  });

  it("logs a normal session out on production", async () => {
    session.getStaffSession.mockResolvedValue({ token: "jwt" });
    const f = stubFetch("{}");
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
