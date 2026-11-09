import { describe, expect, it } from "vitest";
import { rejectCrossSite } from "./bff-proxy";

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
