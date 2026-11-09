import { afterEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { proxyToBackend, rejectCrossSite } from "./bff-proxy";
import { isSimLogin, simTarget } from "./sim-routing";

const req = (headers: Record<string, string>) =>
  new Request("https://app.example/api/auth/borrower/login", { method: "POST", headers });

describe("rejectCrossSite", () => {
  it("allows same-origin JSON", () => {
    expect(rejectCrossSite(req({ "content-type": "application/json", "sec-fetch-site": "same-origin" }))).toBeNull();
  });

  it("allows JSON with no sec-fetch-site (curl, Playwright)", () => {
    expect(rejectCrossSite(req({ "content-type": "Application/JSON; charset=utf-8" }))).toBeNull();
  });

  it("refuses cross-site and same-site fetches", async () => {
    for (const site of ["cross-site", "same-site", "none"]) {
      const res = rejectCrossSite(req({ "content-type": "application/json", "sec-fetch-site": site }));
      expect(res?.status).toBe(403);
    }
  });

  it("refuses a non-JSON or missing content-type", () => {
    expect(rejectCrossSite(req({ "content-type": "text/plain" }))?.status).toBe(403);
    expect(rejectCrossSite(req({}))?.status).toBe(403);
  });
});

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
  const fetchMock = vi.fn(async (..._args: Parameters<typeof fetch>) => new Response("{}", { status: 200 }));
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
