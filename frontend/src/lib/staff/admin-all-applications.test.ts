import { describe, expect, it } from "vitest";
import { ApplicationApiError } from "@/lib/api/applications";
import { retryUnlessClientError } from "./admin-all-applications";

describe("retryUnlessClientError", () => {
  it("never retries a 4xx", () => {
    expect(retryUnlessClientError(0, new ApplicationApiError("no", "FORBIDDEN_ROLE", 403))).toBe(false);
    expect(retryUnlessClientError(0, new ApplicationApiError("no", "HTTP_401", 401))).toBe(false);
    expect(retryUnlessClientError(0, new ApplicationApiError("bad", "BAD_REQUEST", 400))).toBe(false);
    expect(retryUnlessClientError(0, new ApplicationApiError("x", "HTTP_499", 499))).toBe(false);
  });

  it("retries a 5xx or a network failure once", () => {
    const server = new ApplicationApiError("down", "HTTP_503", 503);
    const network = new ApplicationApiError("offline", "NETWORK_ERROR", 0);
    expect(retryUnlessClientError(0, server)).toBe(true);
    expect(retryUnlessClientError(1, server)).toBe(false);
    expect(retryUnlessClientError(0, network)).toBe(true);
    expect(retryUnlessClientError(1, network)).toBe(false);
  });

  it("retries an unknown error once", () => {
    expect(retryUnlessClientError(0, new Error("boom"))).toBe(true);
    expect(retryUnlessClientError(1, new Error("boom"))).toBe(false);
  });
});
