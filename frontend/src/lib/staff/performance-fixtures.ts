/** Test fixture for a {@link StaffPerformanceRow} — shared by the performance unit and page tests. */
import type { StaffPerformanceRow } from "@/lib/api/applications";
import { NO_COLLECTIONS } from "./performance-entities";

export function perfRow(
  overrides: Partial<Omit<StaffPerformanceRow, "collections">> & {
    collections?: Partial<StaffPerformanceRow["collections"]>;
  } = {},
): StaffPerformanceRow {
  const { collections, ...rest } = overrides;
  return {
    staffId: 1,
    staffName: "A",
    role: "CREDIT_EXECUTIVE",
    active: true,
    accepted: 0,
    rejected: 0,
    totalActions: 0,
    activeDays: 0,
    avgTurnaroundMinutes: null,
    pendingNow: 0,
    moneyPaise: 0,
    firstActionAt: null,
    lastActionAt: null,
    verifiedCount: null,
    verifiedPaise: null,
    rejectedPaymentCount: null,
    callsMade: 0,
    actionCounts: {},
    sanctionedPaise: 0,
    releasedPaise: 0,
    daily: [],
    ...rest,
    collections: { ...NO_COLLECTIONS, ...collections },
  };
}
