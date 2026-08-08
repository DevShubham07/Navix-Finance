import { describe, expect, it } from "vitest";
import { collectionBucketCounts } from "./collection-buckets";
import type { CaseView } from "./api/applications";

describe("collectionBucketCounts", () => {
  it("returns live counts and keeps empty buckets at zero", () => {
    const row = (bucket: CaseView["bucket"], id: string) => ({ id, bucket }) as CaseView;
    const counts = collectionBucketCounts([row("T8_T30", "a"), row("T8_T30", "b"), row("UPCOMING", "c")]);
    expect(counts.T8_T30).toBe(2);
    expect(counts.UPCOMING).toBe(1);
    expect(counts.T90_PLUS).toBe(0);
  });
});
