import type { DpdBucket } from "@/lib/api/applications";
import { COLLECTION_BUCKETS } from "@/lib/collection-buckets";

/**
 * Days-past-due stacks for the staff dashboard's two DPD tiles ("DPD split" on Your borrowers,
 * "By DPD bucket" on the Collections desk). Pure shaping only — the page owns colours and markup.
 */

export interface DpdStackSegment {
  key: string;
  /** Short visible label, e.g. "1–30 DPD". The count is always rendered beside it as text. */
  label: string;
  count: number;
  /** This segment's share of the stack, 0–100. 0 for an empty segment or an empty stack. */
  pct: number;
  /** Deep link, set only where the segment maps one-to-one onto a /staff/collections bucket. */
  href?: string;
}

export interface DpdStack {
  total: number;
  /** Every segment in severity order, empty ones included (pct 0), so a legend can show a zero. */
  segments: DpdStackSegment[];
}

type DpdStackPart = { key: string; label: string; count: number; href?: string };

/** Totals the parts and gives each its share. A negative or non-finite count is treated as 0. */
export function dpdStack(parts: ReadonlyArray<DpdStackPart>): DpdStack {
  const clean = parts.map((p) => ({ ...p, count: Number.isFinite(p.count) && p.count > 0 ? p.count : 0 }));
  const total = clean.reduce((s, p) => s + p.count, 0);
  return {
    total,
    segments: clean.map((p) => ({ ...p, pct: total > 0 ? (p.count / total) * 100 : 0 })),
  };
}

/** `/staff/collections` preselected on one bucket — the page reads `?bucket=` (isDpdBucket). */
export function collectionsBucketHref(bucket: DpdBucket): string {
  return `/staff/collections?bucket=${bucket}`;
}

/**
 * The book's three-way split, on CustomerService.bookStats's boundaries: 1–30, 31–60 and more than
 * 60 days late (so the last segment is 61+, not 60+).
 *
 * No links, on purpose: 1–30 spans two collections buckets (T0_T7 + T8_T30) and 61+ spans two more
 * (T60_T90 + T90_PLUS), so no single `?bucket=` shows the same loans; and the collections register
 * lists the whole worklist, not the caller's book.
 */
export function bookDpdStack(dpd: { d1to30: number; d31to60: number; d60plus: number }): DpdStack {
  return dpdStack([
    { key: "d1to30", label: "1–30 DPD", count: dpd.d1to30 },
    { key: "d31to60", label: "31–60 DPD", count: dpd.d31to60 },
    { key: "d60plus", label: "61+ DPD", count: dpd.d60plus },
  ]);
}

/**
 * Collection cases per bucket, in the collections page's own order and labels. Each segment links
 * to that bucket on /staff/collections — the bucket there is the same DpdBucket the case carries.
 */
export function caseBucketStack(counts: Readonly<Record<DpdBucket, number>>): DpdStack {
  return dpdStack(
    COLLECTION_BUCKETS.map(({ bucket, label }) => ({
      key: bucket,
      label,
      count: counts[bucket] ?? 0,
      href: collectionsBucketHref(bucket),
    })),
  );
}
