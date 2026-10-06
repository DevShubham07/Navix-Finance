import { describe, expect, it } from "vitest";
import { bookDpdStack, caseBucketStack, collectionsBucketHref, dpdStack } from "./dashboard-dpd-stack";
import { collectionBucketCounts, isDpdBucket } from "@/lib/collection-buckets";
import type { CaseView } from "@/lib/api/applications";

describe("dpdStack", () => {
  it("totals the parts and gives each its share, keeping empty segments at 0%", () => {
    const s = dpdStack([
      { key: "a", label: "A", count: 10 },
      { key: "b", label: "B", count: 0 },
      { key: "c", label: "C", count: 30 },
    ]);
    expect(s.total).toBe(40);
    expect(s.segments.map((x) => x.pct)).toEqual([25, 0, 75]);
    expect(s.segments.map((x) => x.key)).toEqual(["a", "b", "c"]);
  });

  it("an empty stack has total 0 and no NaN shares", () => {
    const s = dpdStack([
      { key: "a", label: "A", count: 0 },
      { key: "b", label: "B", count: 0 },
    ]);
    expect(s.total).toBe(0);
    expect(s.segments.every((x) => x.pct === 0)).toBe(true);
  });

  it("treats a negative or non-finite count as 0 rather than shrinking the others", () => {
    const s = dpdStack([
      { key: "a", label: "A", count: -3 },
      { key: "b", label: "B", count: Number.NaN },
      { key: "c", label: "C", count: 4 },
    ]);
    expect(s.total).toBe(4);
    expect(s.segments.map((x) => x.count)).toEqual([0, 0, 4]);
    expect(s.segments[2].pct).toBe(100);
  });
});

describe("bookDpdStack", () => {
  it("labels the three book segments on the backend's boundaries (61+, not 60+) and never links", () => {
    const s = bookDpdStack({ d1to30: 10, d31to60: 5, d60plus: 2 });
    expect(s.total).toBe(17);
    expect(s.segments.map((x) => [x.label, x.count])).toEqual([
      ["1–30 DPD", 10],
      ["31–60 DPD", 5],
      ["61+ DPD", 2],
    ]);
    expect(s.segments.every((x) => x.href === undefined)).toBe(true);
  });
});

describe("caseBucketStack", () => {
  it("follows the collections page's bucket order and labels, each linking to its own bucket", () => {
    const row = (bucket: CaseView["bucket"], id: string) => ({ id, bucket }) as CaseView;
    const s = caseBucketStack(
      collectionBucketCounts([row("T8_T30", "a"), row("T8_T30", "b"), row("UPCOMING", "c")]),
    );
    expect(s.total).toBe(3);
    expect(s.segments.map((x) => x.key)).toEqual(["UPCOMING", "T0_T7", "T8_T30", "T30_T60", "T60_T90", "T90_PLUS"]);
    expect(s.segments.find((x) => x.key === "T8_T30")).toMatchObject({
      label: "8–30 DPD",
      count: 2,
      href: "/staff/collections?bucket=T8_T30",
    });
    expect(s.segments.find((x) => x.key === "T90_PLUS")?.count).toBe(0);
  });

  it("every link carries a bucket the collections page accepts", () => {
    const s = caseBucketStack(collectionBucketCounts([]));
    for (const seg of s.segments) {
      const bucket = new URL(seg.href!, "https://x.test").searchParams.get("bucket");
      expect(isDpdBucket(bucket)).toBe(true);
    }
  });
});

describe("collectionsBucketHref", () => {
  it("preselects the bucket via ?bucket=", () => {
    expect(collectionsBucketHref("T30_T60")).toBe("/staff/collections?bucket=T30_T60");
  });
});
