/** Calendar-date arithmetic that never converts through the browser's local timezone. */
export function addIsoCalendarDays(iso: string, days: number): string {
  const [year, month, day] = iso.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/** Credit's sanction is authoritative; the salary-derived limit is only a legacy fallback. */
export function preferredApprovedAmountPaise(value: {
  sanctionedAmountPaise?: number | null;
  eligibleLimitPaise?: number | null;
}): number | null {
  return value.sanctionedAmountPaise ?? value.eligibleLimitPaise ?? null;
}

/** A geo attempt must resolve an address before the borrower can skip manual entry. */
export function needsManualAddressFallback(result: {
  status: string;
  derived?: Record<string, unknown>;
}): boolean {
  return result.status !== "PASS" || typeof result.derived?.address !== "string"
    || result.derived.address.trim().length === 0;
}
