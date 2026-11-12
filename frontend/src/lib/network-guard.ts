/**
 * Network guard — refuses visitors on a VPN, proxy, Tor, relay or a non-India IP.
 *
 * Runs in `middleware.ts` (edge runtime) in front of the whole site. Two independent checks:
 *
 * 1. **Country** — `x-vercel-ip-country`, which Vercel's edge sets from the real client IP (a
 *    client cannot forge it: Vercel overwrites the header). Anything outside
 *    `NETWORK_GUARD_ALLOWED_COUNTRIES` (default `IN`) is refused. Free, no vendor. The header
 *    exists only on Vercel, so local dev / Playwright / the demo stack skip this check.
 * 2. **Anonymizer** — proxycheck.io (`PROXYCHECK_API_KEY`). Catches an *Indian* VPN exit or proxy
 *    that the country check cannot see. Unset key = check skipped.
 *
 * Fail-open on purpose: a proxycheck timeout, quota exhaustion or outage lets the visitor through
 * (and logs). A vendor hiccup must never take the whole site — repayments included — offline.
 *
 * A clean verdict is remembered in a short-lived HMAC-signed cookie bound to the client IP, so
 * proxycheck is called once per visitor per {@link CLEAN_TTL_SECONDS}, not on every page and
 * every polled BFF call. Switching a VPN on afterwards changes the IP, which voids the cookie.
 *
 * Verified search-engine crawlers (Google, Bing, Apple — user-agent claim confirmed against the
 * engine's published IP ranges, see `verified-crawlers.ts`) are let through so the site stays indexed.
 *
 * Kill switch: `NETWORK_GUARD_DISABLED=true`.
 */

import { verifiedCrawler } from "./verified-crawlers";

export type BlockReason = "country" | "vpn" | "proxy" | "tor";

/**
 * `unverified`: the lookup failed and we let the visitor through (fail-open) — never cached as clean.
 * `crawler`: a verified search-engine bot, let through by engine name.
 */
export type GuardVerdict =
  | { blocked: false; unverified?: boolean; crawler?: string }
  | { blocked: true; reason: BlockReason; detail?: string };

export const CLEAN_COOKIE = "dbx_net_ok";
export const CLEAN_TTL_SECONDS = 30 * 60;
const LOOKUP_TIMEOUT_MS = 1500;
const BLOCK_CACHE_TTL_MS = 10 * 60 * 1000;
const BLOCK_CACHE_MAX = 5000;

export interface GuardConfig {
  disabled: boolean;
  allowedCountries: Set<string>;
  proxycheckKey: string | null;
}

export function readGuardConfig(env: Record<string, string | undefined> = process.env): GuardConfig {
  const countries = (env.NETWORK_GUARD_ALLOWED_COUNTRIES ?? "IN")
    .split(",")
    .map((c) => c.trim().toUpperCase())
    .filter(Boolean);
  return {
    disabled: env.NETWORK_GUARD_DISABLED === "true",
    allowedCountries: new Set(countries),
    proxycheckKey: env.PROXYCHECK_API_KEY?.trim() || null,
  };
}

/** The client IP as Vercel's edge reports it (`x-real-ip`, else the first `x-forwarded-for` hop). */
export function clientIp(headers: Headers): string | null {
  const real = headers.get("x-real-ip")?.trim();
  if (real) return real;
  const first = headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  return first || null;
}

/** Loopback / RFC1918 / link-local / ULA — never worth a lookup (local dev, internal hops). */
export function isPrivateIp(ip: string): boolean {
  const v = ip.toLowerCase();
  if (v === "::1" || v === "localhost" || v.startsWith("fe80:") || v.startsWith("fc") || v.startsWith("fd")) {
    return true;
  }
  const v4 = v.startsWith("::ffff:") ? v.slice(7) : v;
  const m = /^(\d+)\.(\d+)\.\d+\.\d+$/.exec(v4);
  if (!m) return false;
  const a = Number(m[1]);
  const b = Number(m[2]);
  return (
    a === 10 ||
    a === 127 ||
    a === 0 ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 169 && b === 254) ||
    (a === 100 && b >= 64 && b <= 127) // CGNAT
  );
}

/** Country rule. `null` country (not on Vercel) = no opinion. */
export function checkCountry(country: string | null, config: GuardConfig): GuardVerdict {
  if (!country || config.allowedCountries.size === 0) return { blocked: false };
  const cc = country.toUpperCase();
  return config.allowedCountries.has(cc) ? { blocked: false } : { blocked: true, reason: "country", detail: cc };
}

/** Interpret one proxycheck.io v2 per-IP record. */
export function interpretProxycheck(record: unknown): GuardVerdict {
  if (!record || typeof record !== "object") return { blocked: false };
  const r = record as { proxy?: string; type?: string; vpn?: string };
  const type = (r.type ?? "").toUpperCase();
  if (type === "TOR") return { blocked: true, reason: "tor", detail: type };
  if (r.vpn === "yes" || type === "VPN") return { blocked: true, reason: "vpn", detail: type || "VPN" };
  if (r.proxy === "yes") return { blocked: true, reason: "proxy", detail: type || "PROXY" };
  return { blocked: false };
}

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

/** Ask proxycheck.io about one IP. Any failure resolves to "not blocked" (fail-open). */
export async function lookupProxycheck(ip: string, key: string, fetchImpl: FetchLike = fetch): Promise<GuardVerdict> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), LOOKUP_TIMEOUT_MS);
  try {
    const url = `https://proxycheck.io/v2/${encodeURIComponent(ip)}?key=${encodeURIComponent(key)}&vpn=3&asn=1`;
    const res = await fetchImpl(url, { signal: controller.signal, cache: "no-store" });
    if (!res.ok) {
      console.warn(`[network-guard] proxycheck HTTP ${res.status}; allowing ${ip}`);
      return { blocked: false, unverified: true };
    }
    const body = (await res.json()) as Record<string, unknown> & { status?: string; message?: string };
    // "ok" and "warning" both carry a verdict; "denied"/"error" (bad key, quota) do not.
    if (body.status !== "ok" && body.status !== "warning") {
      console.warn(`[network-guard] proxycheck ${body.status}: ${body.message ?? ""}; allowing ${ip}`);
      return { blocked: false, unverified: true };
    }
    return interpretProxycheck(body[ip]);
  } catch (err) {
    console.warn(`[network-guard] proxycheck lookup failed (${(err as Error).name}); allowing ${ip}`);
    return { blocked: false, unverified: true };
  } finally {
    clearTimeout(timer);
  }
}

// ---- verdict caching -------------------------------------------------------------------------

/** Per-isolate memory of recently blocked IPs, so a blocked visitor hammering refresh costs nothing. */
const blockedCache = new Map<string, { verdict: GuardVerdict; until: number }>();

export function cachedBlock(ip: string, now = Date.now()): GuardVerdict | null {
  const hit = blockedCache.get(ip);
  if (!hit) return null;
  if (hit.until < now) {
    blockedCache.delete(ip);
    return null;
  }
  return hit.verdict;
}

export function rememberBlock(ip: string, verdict: GuardVerdict, now = Date.now()): void {
  if (blockedCache.size >= BLOCK_CACHE_MAX) {
    const oldest = blockedCache.keys().next().value;
    if (oldest !== undefined) blockedCache.delete(oldest);
  }
  blockedCache.set(ip, { verdict, until: now + BLOCK_CACHE_TTL_MS });
}

/** Test seam. */
export function clearBlockCache(): void {
  blockedCache.clear();
}

async function hmac(secret: string, message: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
  ]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(message)));
  let bin = "";
  for (const byte of sig) bin += String.fromCharCode(byte);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Signed `exp.sig` token vouching that `ip` passed the anonymizer check until `exp`. */
export async function signCleanToken(ip: string, secret: string, nowSeconds = Math.floor(Date.now() / 1000)) {
  const exp = nowSeconds + CLEAN_TTL_SECONDS;
  return `${exp}.${await hmac(secret, `${ip}|${exp}`)}`;
}

export async function verifyCleanToken(
  token: string | undefined,
  ip: string,
  secret: string,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<boolean> {
  if (!token) return false;
  const dot = token.indexOf(".");
  if (dot <= 0) return false;
  const exp = Number(token.slice(0, dot));
  if (!Number.isFinite(exp) || exp < nowSeconds) return false;
  const expected = await hmac(secret, `${ip}|${exp}`);
  const given = token.slice(dot + 1);
  if (expected.length !== given.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ given.charCodeAt(i);
  return diff === 0;
}

// ---- the decision ----------------------------------------------------------------------------

export interface GuardOutcome {
  verdict: GuardVerdict;
  /** Set when a fresh proxycheck lookup came back clean — the caller should set {@link CLEAN_COOKIE}. */
  newCleanToken?: string;
}

export async function evaluateRequest(
  headers: Headers,
  cleanCookie: string | undefined,
  config: GuardConfig,
  fetchImpl: FetchLike = fetch,
): Promise<GuardOutcome> {
  if (config.disabled) return { verdict: { blocked: false } };

  const ip = clientIp(headers);
  const crawler = await verifiedCrawler(headers.get("user-agent"), ip, fetchImpl);
  if (crawler) return { verdict: { blocked: false, crawler } };

  const country = checkCountry(headers.get("x-vercel-ip-country"), config);
  if (country.blocked) return { verdict: country };

  const key = config.proxycheckKey;
  if (!key || !ip || isPrivateIp(ip)) return { verdict: { blocked: false } };

  if (await verifyCleanToken(cleanCookie, ip, key)) return { verdict: { blocked: false } };

  const cached = cachedBlock(ip);
  if (cached) return { verdict: cached };

  const verdict = await lookupProxycheck(ip, key, fetchImpl);
  if (verdict.blocked) {
    rememberBlock(ip, verdict);
    return { verdict };
  }
  if (verdict.unverified) return { verdict }; // retry on the next request rather than vouch for it
  return { verdict, newCleanToken: await signCleanToken(ip, key) };
}
