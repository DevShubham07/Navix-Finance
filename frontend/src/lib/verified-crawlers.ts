/**
 * Verified search-engine crawlers — exempt from the network guard so the marketing site stays
 * indexed (Googlebot and friends crawl mostly from US datacenter IPs, which the guard would 403).
 *
 * A user-agent alone proves nothing (anyone can send "Googlebot"), so a crawler is exempt only
 * when its user-agent claims a known engine **and** its IP sits inside that engine's officially
 * published crawler ranges. Reverse-DNS verification is not available in the edge runtime; the
 * published JSON lists are the engines' own documented alternative.
 *
 * Ranges are fetched lazily — only when a request claims to be a crawler — and kept per isolate
 * for {@link RANGES_TTL_MS}. Fails **closed**: if a feed can't be read the request is treated as
 * an ordinary visitor (a crawler briefly sees a 403 and retries later; a bypass never opens).
 */

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

interface Engine {
  name: string;
  userAgent: RegExp;
  feeds: string[];
}

export const ENGINES: Engine[] = [
  {
    name: "google",
    userAgent: /Googlebot|Google-InspectionTool|GoogleOther|Storebot-Google|AdsBot-Google|Mediapartners-Google/i,
    feeds: [
      "https://developers.google.com/static/search/apis/ipranges/googlebot.json",
      "https://developers.google.com/static/search/apis/ipranges/special-crawlers.json",
    ],
  },
  {
    name: "bing",
    userAgent: /bingbot|BingPreview|adidxbot|MicrosoftPreview/i,
    feeds: ["https://www.bing.com/toolbox/bingbot.json"],
  },
  {
    name: "apple",
    userAgent: /Applebot/i,
    feeds: ["https://search.developer.apple.com/applebot.json"],
  },
];

const RANGES_TTL_MS = 12 * 60 * 60 * 1000;
const FAILURE_TTL_MS = 5 * 60 * 1000;
const FEED_TIMEOUT_MS = 2000;

// ---- IP / CIDR arithmetic (BigInt, works for v4 and v6) --------------------------------------

// BigInt() calls, not `B0` literals: tsconfig targets below ES2020.
const B0 = BigInt(0);
const B8 = BigInt(8);
const B16 = BigInt(16);
const B32 = BigInt(32);
const MASK16 = BigInt(0xffff);
const MASK32 = BigInt(0xffffffff);

interface Cidr {
  v6: boolean;
  base: bigint;
  bits: number;
}

function parseV4(ip: string): bigint | null {
  const parts = ip.split(".");
  if (parts.length !== 4) return null;
  let n = B0;
  for (const p of parts) {
    if (!/^\d{1,3}$/.test(p) || Number(p) > 255) return null;
    n = (n << B8) | BigInt(Number(p));
  }
  return n;
}

function parseV6(ip: string): bigint | null {
  const halves = ip.split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  // An embedded IPv4 tail (::ffff:1.2.3.4) counts as two groups.
  const last = tail.length ? tail : head;
  if (last.length && last[last.length - 1].includes(".")) {
    const v4 = parseV4(last.pop()!);
    if (v4 === null) return null;
    last.push((v4 >> B16).toString(16), (v4 & MASK16).toString(16));
  }
  const missing = 8 - head.length - tail.length;
  if (halves.length === 1 ? missing !== 0 : missing < 1) return null;
  const groups = [...head, ...Array(halves.length === 2 ? missing : 0).fill("0"), ...tail];
  let n = B0;
  for (const g of groups) {
    if (!/^[0-9a-f]{1,4}$/i.test(g)) return null;
    n = (n << B16) | BigInt(parseInt(g, 16));
  }
  return n;
}

/** Parse an address; IPv4-mapped IPv6 (::ffff:a.b.c.d) is returned as plain IPv4. */
export function parseIp(ip: string): { v6: boolean; value: bigint } | null {
  const v4 = parseV4(ip);
  if (v4 !== null) return { v6: false, value: v4 };
  const v6 = parseV6(ip);
  if (v6 === null) return null;
  if (v6 >> B32 === MASK16) return { v6: false, value: v6 & MASK32 };
  return { v6: true, value: v6 };
}

export function parseCidr(cidr: string): Cidr | null {
  const [addr, len] = cidr.trim().split("/");
  const ip = parseIp(addr);
  if (!ip) return null;
  const max = ip.v6 ? 128 : 32;
  const bits = len === undefined ? max : Number(len);
  if (!Number.isInteger(bits) || bits < 0 || bits > max) return null;
  return { v6: ip.v6, base: ip.value >> BigInt(max - bits), bits };
}

export function inCidr(ip: { v6: boolean; value: bigint }, cidr: Cidr): boolean {
  if (ip.v6 !== cidr.v6) return false;
  const max = ip.v6 ? 128 : 32;
  return ip.value >> BigInt(max - cidr.bits) === cidr.base;
}

/** Read an engine feed: `{ prefixes: [{ ipv4Prefix } | { ipv6Prefix }] }`. */
export function parseFeed(body: unknown): Cidr[] {
  const prefixes = (body as { prefixes?: unknown })?.prefixes;
  if (!Array.isArray(prefixes)) return [];
  const out: Cidr[] = [];
  for (const p of prefixes) {
    const raw = (p as { ipv4Prefix?: unknown; ipv6Prefix?: unknown }) ?? {};
    const s = typeof raw.ipv4Prefix === "string" ? raw.ipv4Prefix : raw.ipv6Prefix;
    if (typeof s === "string") {
      const c = parseCidr(s);
      if (c) out.push(c);
    }
  }
  return out;
}

// ---- range cache -----------------------------------------------------------------------------

const rangeCache = new Map<string, { ranges: Cidr[] | null; until: number }>();

/** Test seam. */
export function clearCrawlerCache(): void {
  rangeCache.clear();
}

async function engineRanges(engine: Engine, fetchImpl: FetchLike, now: number): Promise<Cidr[] | null> {
  const hit = rangeCache.get(engine.name);
  if (hit && hit.until > now) return hit.ranges;

  const results = await Promise.all(
    engine.feeds.map(async (url) => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), FEED_TIMEOUT_MS);
      try {
        const res = await fetchImpl(url, { signal: controller.signal, cache: "no-store" });
        return res.ok ? parseFeed(await res.json()) : [];
      } catch {
        return [];
      } finally {
        clearTimeout(timer);
      }
    }),
  );
  const ranges = results.flat();
  if (ranges.length === 0) {
    console.warn(`[network-guard] could not load ${engine.name} crawler ranges; not exempting`);
    rangeCache.set(engine.name, { ranges: null, until: now + FAILURE_TTL_MS });
    return null;
  }
  rangeCache.set(engine.name, { ranges, until: now + RANGES_TTL_MS });
  return ranges;
}

/**
 * The engine name when `userAgent` claims a known search crawler AND `ip` is inside that engine's
 * published ranges; otherwise null. Never calls out unless the user-agent claims a crawler.
 */
export async function verifiedCrawler(
  userAgent: string | null,
  ip: string | null,
  fetchImpl: FetchLike = fetch,
  now = Date.now(),
): Promise<string | null> {
  if (!userAgent || !ip) return null;
  const engine = ENGINES.find((e) => e.userAgent.test(userAgent));
  if (!engine) return null;
  const parsed = parseIp(ip);
  if (!parsed) return null;
  const ranges = await engineRanges(engine, fetchImpl, now);
  if (!ranges) return null;
  return ranges.some((c) => inCidr(parsed, c)) ? engine.name : null;
}
