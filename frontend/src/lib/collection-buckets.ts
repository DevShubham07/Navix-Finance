import type { DpdBucket } from "@/lib/api/applications";

export const COLLECTION_BUCKETS: ReadonlyArray<{ bucket: DpdBucket; label: string }> = [
  { bucket: "UPCOMING", label: "Upcoming" },
  { bucket: "T0_T7", label: "1–7 DPD" },
  { bucket: "T8_T30", label: "8–30 DPD" },
  { bucket: "T30_T60", label: "31–60 DPD" },
  { bucket: "T60_T90", label: "61–90 DPD" },
  { bucket: "T90_PLUS", label: "90+ DPD" },
];

/** Rows per bucket. Takes anything carrying a `bucket` — worklist rows today, cases historically. */
export function collectionBucketCounts(rows: ReadonlyArray<{ bucket: DpdBucket }>): Record<DpdBucket, number> {
  const counts = Object.fromEntries(COLLECTION_BUCKETS.map(({ bucket }) => [bucket, 0])) as Record<DpdBucket, number>;
  for (const item of rows) counts[item.bucket] += 1;
  return counts;
}

export function isDpdBucket(value: string | null): value is DpdBucket {
  return COLLECTION_BUCKETS.some(({ bucket }) => bucket === value);
}
