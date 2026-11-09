/**
 * The named ranges and fixed choices the register dropdowns offer, in one place so Customers, Loans
 * and the application queues read the same: "₹5,000 to ₹10,000" is the same band everywhere.
 *
 * Bounds are DISPLAY units (rupees, points) and inclusive, like `ColumnFilter`. Each band starts a
 * paisa above the one before (₹5,000.01) so a value sits in exactly one band and the server's
 * rupees-to-paise scaling keeps them apart. Labels say "₹5,000 to ₹10,000" because that is how a
 * person reads it; the lower edge being the next paisa is invisible and exact.
 */

import type { FilterBand } from "@/components/staff/column-filter";
import { COLLECTION_BUCKETS } from "@/lib/collection-buckets";

/** Loan amount, principal and outstanding. */
export const AMOUNT_BANDS: FilterBand[] = [
  { label: "Up to ₹5,000", max: 5000 },
  { label: "₹5,000 to ₹10,000", min: 5000.01, max: 10000 },
  { label: "₹10,000 to ₹25,000", min: 10000.01, max: 25000 },
  { label: "₹25,000 to ₹50,000", min: 25000.01, max: 50000 },
  { label: "Above ₹50,000", min: 50000.01 },
];
export const LOAN_AMOUNT_BANDS = AMOUNT_BANDS;

/** Monthly salary. */
export const SALARY_BANDS: FilterBand[] = [
  { label: "Up to ₹20,000", max: 20000 },
  { label: "₹20,000 to ₹35,000", min: 20000.01, max: 35000 },
  { label: "₹35,000 to ₹50,000", min: 35000.01, max: 50000 },
  { label: "₹50,000 to ₹75,000", min: 50000.01, max: 75000 },
  { label: "₹75,000 to ₹1,00,000", min: 75000.01, max: 100000 },
  { label: "Above ₹1,00,000", min: 100000.01 },
];

/** Bureau score; whole numbers, so the next band simply starts one higher. */
export const CREDIT_SCORE_BANDS: FilterBand[] = [
  { label: "Below 600", max: 599 },
  { label: "600–649", min: 600, max: 649 },
  { label: "650–699", min: 650, max: 699 },
  { label: "700–749", min: 700, max: 749 },
  { label: "750 and above", min: 750 },
];

/** Which advance this is for the borrower. */
export const LOAN_CYCLE_BANDS: FilterBand[] = [
  { label: "1", min: 1, max: 1 },
  { label: "2", min: 2, max: 2 },
  { label: "3", min: 3, max: 3 },
  { label: "4+", min: 4 },
];

const DPD_LABEL: Record<string, string> = {
  UPCOMING: "Not yet due",
  T0_T7: "1–7 days",
  T8_T30: "8–30 days",
  T30_T60: "31–60 days",
  T60_T90: "61–90 days",
  T90_PLUS: "90+ days",
};

/**
 * The DPD buckets as a closed checklist, in the Collections tab's order and scheme
 * (`DpdCalculator`), so a count here matches the Collections tab. The stored value is the bucket key.
 */
export const DPD_BUCKET_OPTIONS: Array<{ raw: string; label: string }> = COLLECTION_BUCKETS.map(
  ({ bucket }) => ({ raw: bucket, label: DPD_LABEL[bucket] ?? bucket }),
);

/** Staff-only risk category (never shown to a borrower). */
export const RISK_OPTIONS: Array<{ raw: string; label: string }> = ["A", "B", "C", "D"].map((c) => ({
  raw: c,
  label: `Category ${c}`,
}));
