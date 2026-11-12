import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  checkCountry,
  clearBlockCache,
  clientIp,
  evaluateRequest,
  interpretProxycheck,
  isPrivateIp,
  readGuardConfig,
  signCleanToken,
  verifyCleanToken,
} from "./network-guard";

const KEY = "test-key";
const config = (over: Record<string, string> = {}) => readGuardConfig({ PROXYCHECK_API_KEY: KEY, ...over });

function headers(h: Record<string, string>) {
  return new Headers(h);
}

function proxycheckReturning(ip: string, record: object, status = "ok") {
  return vi.fn(async () => new Response(JSON.stringify({ status, [ip]: record }), { status: 200 }));
}

beforeEach(() => clearBlockCache());

describe("country rule", () => {
  it("allows India and blocks elsewhere; no header = no opinion", () => {
    const c = config();
    expect(checkCountry("IN", c).blocked).toBe(false);
    expect(checkCountry("us", c)).toEqual({ blocked: true, reason: "country", detail: "US" });
    expect(checkCountry(null, c).blocked).toBe(false);
  });

  it("honours a configured allow-list", () => {
    expect(checkCountry("AE", config({ NETWORK_GUARD_ALLOWED_COUNTRIES: "IN, ae" })).blocked).toBe(false);
  });
});

describe("helpers", () => {
  it("reads the client IP from Vercel's headers", () => {
    expect(clientIp(headers({ "x-real-ip": "49.36.1.2" }))).toBe("49.36.1.2");
    expect(clientIp(headers({ "x-forwarded-for": "49.36.1.2, 10.0.0.1" }))).toBe("49.36.1.2");
    expect(clientIp(headers({}))).toBeNull();
  });

  it("recognises private addresses", () => {
    for (const ip of ["127.0.0.1", "10.1.2.3", "192.168.0.4", "172.20.0.1", "::1", "::ffff:10.0.0.1"]) {
      expect(isPrivateIp(ip)).toBe(true);
    }
    expect(isPrivateIp("49.36.1.2")).toBe(false);
  });

  it("maps proxycheck records to reasons", () => {
    expect(interpretProxycheck({ proxy: "no" }).blocked).toBe(false);
    expect(interpretProxycheck({ proxy: "yes", type: "VPN" })).toMatchObject({ reason: "vpn" });
    expect(interpretProxycheck({ proxy: "no", vpn: "yes" })).toMatchObject({ reason: "vpn" });
    expect(interpretProxycheck({ proxy: "yes", type: "TOR" })).toMatchObject({ reason: "tor" });
    expect(interpretProxycheck({ proxy: "yes", type: "SOCKS5" })).toMatchObject({ reason: "proxy" });
  });

  it("signs a clean token bound to the IP and expiry", async () => {
    const t = await signCleanToken("49.36.1.2", KEY, 1000);
    expect(await verifyCleanToken(t, "49.36.1.2", KEY, 1001)).toBe(true);
    expect(await verifyCleanToken(t, "49.36.9.9", KEY, 1001)).toBe(false); // IP changed (VPN on)
    expect(await verifyCleanToken(t, "49.36.1.2", "other", 1001)).toBe(false);
    expect(await verifyCleanToken(t, "49.36.1.2", KEY, 1000 + 31 * 60)).toBe(false); // expired
    expect(await verifyCleanToken("garbage", "49.36.1.2", KEY, 1001)).toBe(false);
  });
});

describe("evaluateRequest", () => {
  const ip = "49.36.1.2";

  it("blocks a non-India visitor without calling the vendor", async () => {
    const f = vi.fn();
    const out = await evaluateRequest(headers({ "x-vercel-ip-country": "DE", "x-real-ip": ip }), undefined, config(), f);
    expect(out.verdict).toMatchObject({ blocked: true, reason: "country" });
    expect(f).not.toHaveBeenCalled();
  });

  it("blocks an Indian VPN exit and caches the block", async () => {
    const f = proxycheckReturning(ip, { proxy: "yes", type: "VPN" });
    const h = headers({ "x-vercel-ip-country": "IN", "x-real-ip": ip });
    expect((await evaluateRequest(h, undefined, config(), f)).verdict).toMatchObject({ reason: "vpn" });
    expect((await evaluateRequest(h, undefined, config(), f)).verdict).toMatchObject({ reason: "vpn" });
    expect(f).toHaveBeenCalledTimes(1);
  });

  it("issues a clean token, and the token skips the next lookup", async () => {
    const f = proxycheckReturning(ip, { proxy: "no" });
    const h = headers({ "x-vercel-ip-country": "IN", "x-real-ip": ip });
    const first = await evaluateRequest(h, undefined, config(), f);
    expect(first.verdict.blocked).toBe(false);
    expect(first.newCleanToken).toBeTruthy();
    const second = await evaluateRequest(h, first.newCleanToken, config(), f);
    expect(second.verdict.blocked).toBe(false);
    expect(f).toHaveBeenCalledTimes(1);
  });

  it("fails open, without vouching, when the vendor errors or times out", async () => {
    const h = headers({ "x-real-ip": ip });
    const denied = proxycheckReturning(ip, {}, "denied");
    const out = await evaluateRequest(h, undefined, config(), denied);
    expect(out.verdict.blocked).toBe(false);
    expect(out.newCleanToken).toBeUndefined();

    const broken = vi.fn(async () => {
      throw new Error("network down");
    });
    expect((await evaluateRequest(h, undefined, config(), broken)).verdict.blocked).toBe(false);
  });

  it("skips the vendor without a key, for private IPs, and when disabled", async () => {
    const f = vi.fn();
    await evaluateRequest(headers({ "x-real-ip": ip }), undefined, readGuardConfig({}), f);
    await evaluateRequest(headers({ "x-real-ip": "127.0.0.1" }), undefined, config(), f);
    const off = await evaluateRequest(
      headers({ "x-vercel-ip-country": "US", "x-real-ip": ip }),
      undefined,
      config({ NETWORK_GUARD_DISABLED: "true" }),
      f,
    );
    expect(off.verdict.blocked).toBe(false);
    expect(f).not.toHaveBeenCalled();
  });
});
