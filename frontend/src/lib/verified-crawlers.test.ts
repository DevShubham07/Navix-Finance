import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearCrawlerCache, inCidr, parseCidr, parseFeed, parseIp, verifiedCrawler } from "./verified-crawlers";
import { clearBlockCache, evaluateRequest, readGuardConfig } from "./network-guard";

const GOOGLEBOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
const BINGBOT_UA = "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)";
const CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0 Safari/537.36";

const FEEDS: Record<string, object> = {
  "googlebot.json": { prefixes: [{ ipv4Prefix: "66.249.64.0/27" }, { ipv6Prefix: "2001:4860:4801:10::/64" }] },
  "special-crawlers.json": { prefixes: [{ ipv4Prefix: "66.249.90.64/27" }] },
  "bingbot.json": { prefixes: [{ ipv4Prefix: "157.55.39.0/24" }] },
};

function feedFetch() {
  return vi.fn(async (url: string) => {
    const name = url.split("/").pop()!;
    return FEEDS[name]
      ? new Response(JSON.stringify(FEEDS[name]), { status: 200 })
      : new Response("nope", { status: 404 });
  });
}

beforeEach(() => {
  clearCrawlerCache();
  clearBlockCache();
});

describe("CIDR maths", () => {
  it("matches v4, v6 and IPv4-mapped v6", () => {
    const v4 = parseCidr("66.249.64.0/27")!;
    expect(inCidr(parseIp("66.249.64.31")!, v4)).toBe(true);
    expect(inCidr(parseIp("66.249.64.32")!, v4)).toBe(false);
    expect(inCidr(parseIp("::ffff:66.249.64.5")!, v4)).toBe(true);

    const v6 = parseCidr("2001:4860:4801:10::/64")!;
    expect(inCidr(parseIp("2001:4860:4801:10::abcd")!, v6)).toBe(true);
    expect(inCidr(parseIp("2001:4860:4801:11::1")!, v6)).toBe(false);
    expect(inCidr(parseIp("66.249.64.5")!, v6)).toBe(false);
  });

  it("rejects malformed input", () => {
    expect(parseIp("999.1.1.1")).toBeNull();
    expect(parseIp("1::2::3")).toBeNull();
    expect(parseIp("not-an-ip")).toBeNull();
    expect(parseCidr("10.0.0.0/33")).toBeNull();
    expect(parseFeed({ nothing: true })).toEqual([]);
    expect(parseFeed({ prefixes: [{ ipv4Prefix: "bad" }, { ipv4Prefix: "1.2.3.0/24" }] })).toHaveLength(1);
  });
});

describe("verifiedCrawler", () => {
  it("accepts a Googlebot UA from Google's ranges (either feed) and Bing from Bing's", async () => {
    const f = feedFetch();
    expect(await verifiedCrawler(GOOGLEBOT_UA, "66.249.64.10", f)).toBe("google");
    expect(await verifiedCrawler(GOOGLEBOT_UA, "66.249.90.70", f)).toBe("google");
    expect(await verifiedCrawler(GOOGLEBOT_UA, "2001:4860:4801:10::1", f)).toBe("google");
    expect(await verifiedCrawler(BINGBOT_UA, "157.55.39.8", f)).toBe("bing");
  });

  it("refuses a spoofed UA from an unlisted IP, and a Bing UA from Google's ranges", async () => {
    const f = feedFetch();
    expect(await verifiedCrawler(GOOGLEBOT_UA, "185.220.101.4", f)).toBeNull();
    expect(await verifiedCrawler(BINGBOT_UA, "66.249.64.10", f)).toBeNull();
  });

  it("never fetches for an ordinary browser", async () => {
    const f = feedFetch();
    expect(await verifiedCrawler(CHROME_UA, "66.249.64.10", f)).toBeNull();
    expect(f).not.toHaveBeenCalled();
  });

  it("caches the ranges", async () => {
    const f = feedFetch();
    await verifiedCrawler(GOOGLEBOT_UA, "66.249.64.10", f);
    await verifiedCrawler(GOOGLEBOT_UA, "66.249.64.11", f);
    expect(f).toHaveBeenCalledTimes(2); // the two Google feeds, once
  });

  it("fails closed when the feeds can't be read", async () => {
    const down = vi.fn(async () => {
      throw new Error("down");
    });
    expect(await verifiedCrawler(GOOGLEBOT_UA, "66.249.64.10", down)).toBeNull();
  });
});

describe("guard integration", () => {
  const config = readGuardConfig({ PROXYCHECK_API_KEY: "k" });

  it("lets a verified Googlebot through from the US without a proxycheck lookup", async () => {
    const f = feedFetch();
    const h = new Headers({ "user-agent": GOOGLEBOT_UA, "x-real-ip": "66.249.64.10", "x-vercel-ip-country": "US" });
    const out = await evaluateRequest(h, undefined, config, f);
    expect(out.verdict).toEqual({ blocked: false, crawler: "google" });
    expect(f.mock.calls.some(([u]) => String(u).includes("proxycheck"))).toBe(false);
  });

  it("still blocks a spoofed Googlebot UA from the US", async () => {
    const f = feedFetch();
    const h = new Headers({ "user-agent": GOOGLEBOT_UA, "x-real-ip": "185.220.101.4", "x-vercel-ip-country": "US" });
    expect((await evaluateRequest(h, undefined, config, f)).verdict).toMatchObject({ blocked: true, reason: "country" });
  });
});
