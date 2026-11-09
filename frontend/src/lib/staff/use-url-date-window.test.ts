import { describe, expect, it } from "vitest";
import { readDateWindow, writeDateWindow } from "@/lib/staff/use-url-date-window";

describe("date window in the URL", () => {
  it("is all time when the URL says nothing", () => {
    expect(readDateWindow(new URLSearchParams("seg=active"))).toEqual({ period: "ALL", custom: {} });
  });

  it("round-trips a custom range and leaves the other params alone", () => {
    const qs = writeDateWindow("seg=active&page=3", "CUSTOM", { from: "2026-10-01", to: "2026-10-31" });
    const p = new URLSearchParams(qs);
    expect(p.get("seg")).toBe("active");
    expect(p.get("page")).toBeNull(); // a changed window starts at page 1
    expect(readDateWindow(p)).toEqual({ period: "CUSTOM", custom: { from: "2026-10-01", to: "2026-10-31" } });
  });

  it("stores a relative period as the period, so a link still means today tomorrow", () => {
    const qs = writeDateWindow("", "TODAY", {});
    expect(qs).toBe("win=TODAY");
    expect(readDateWindow(new URLSearchParams(qs)).period).toBe("TODAY");
  });

  it("clears every window param for All time", () => {
    expect(writeDateWindow("win=CUSTOM&wfrom=2026-01-01&wto=2026-01-02&q=x", "ALL", {})).toBe("q=x");
  });

  it("ignores a malformed day rather than passing it to the server", () => {
    expect(readDateWindow(new URLSearchParams("win=CUSTOM&wfrom=garbage&wto=2026-01-02")).custom).toEqual({
      from: undefined,
      to: "2026-01-02",
    });
  });
});
