import { describe, expect, it } from "vitest";
import { matchesFilter } from "@/lib/staff/column-filter-match";
import {
  AMOUNT_BANDS,
  CREDIT_SCORE_BANDS,
  DPD_BUCKET_OPTIONS,
  LOAN_CYCLE_BANDS,
  SALARY_BANDS,
} from "@/lib/staff/filter-bands";

const inBand = (bands: typeof AMOUNT_BANDS, i: number, n: number) =>
  matchesFilter({ type: "num", min: bands[i].min, max: bands[i].max }, { text: "", raw: "", num: n });

/** Every value belongs to exactly one band — no gap, no double count at a boundary. */
function owners(bands: typeof AMOUNT_BANDS, n: number) {
  return bands.map((_, i) => i).filter((i) => inBand(bands, i, n));
}

describe("named ranges", () => {
  it("put each amount in exactly one band, boundaries included", () => {
    for (const n of [0, 1, 4999.99, 5000, 5000.01, 10000, 10000.01, 25000, 25000.01, 50000, 50000.01, 9_999_999]) {
      expect(owners(AMOUNT_BANDS, n), String(n)).toHaveLength(1);
    }
    expect(owners(AMOUNT_BANDS, 5000)).toEqual([0]);
    expect(owners(AMOUNT_BANDS, 5000.01)).toEqual([1]);
    expect(owners(AMOUNT_BANDS, 60000)).toEqual([4]);
  });

  it("cover salary up to ₹1,00,000+ with no overlap", () => {
    for (const n of [10000, 20000, 20000.01, 35000, 50000, 75000, 100000, 100000.01, 250000]) {
      expect(owners(SALARY_BANDS, n), String(n)).toHaveLength(1);
    }
    expect(SALARY_BANDS.at(-1)?.label).toBe("Above ₹1,00,000");
  });

  it("split credit score at 600/650/700/750", () => {
    expect(owners(CREDIT_SCORE_BANDS, 599)).toEqual([0]);
    expect(owners(CREDIT_SCORE_BANDS, 600)).toEqual([1]);
    expect(owners(CREDIT_SCORE_BANDS, 649)).toEqual([1]);
    expect(owners(CREDIT_SCORE_BANDS, 650)).toEqual([2]);
    expect(owners(CREDIT_SCORE_BANDS, 749)).toEqual([3]);
    expect(owners(CREDIT_SCORE_BANDS, 750)).toEqual([4]);
  });

  it("read loan cycle as 1, 2, 3, 4+", () => {
    expect(LOAN_CYCLE_BANDS.map((b) => b.label)).toEqual(["1", "2", "3", "4+"]);
    expect(owners(LOAN_CYCLE_BANDS, 9)).toEqual([3]);
  });

  it("list the DPD buckets in the Collections order with their stored keys", () => {
    expect(DPD_BUCKET_OPTIONS.map((o) => o.raw)).toEqual(["UPCOMING", "T0_T7", "T8_T30", "T30_T60", "T60_T90", "T90_PLUS"]);
    expect(DPD_BUCKET_OPTIONS.map((o) => o.label)).toEqual([
      "Not yet due", "1–7 days", "8–30 days", "31–60 days", "61–90 days", "90+ days",
    ]);
  });
});
