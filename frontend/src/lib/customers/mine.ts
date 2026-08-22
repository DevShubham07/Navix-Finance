/**
 * "My book" membership for a staffer's personal dashboard. Deliberately free of any API-client
 * import (same reason as segments.ts) so this stays a pure, dependency-free helper — the caller
 * passes in exactly the fields it needs.
 */

import type { CustomerSummary, DecisionView } from "@/lib/api/applications";

/** true when this customer is "mine": I decided on them, or they're allocated to me. */
export function isMine(
  c: Pick<CustomerSummary, "customerId" | "ownerStaffId">,
  staffId: number,
  decidedCustomerIds: Set<number>,
): boolean {
  return c.ownerStaffId === staffId || decidedCustomerIds.has(c.customerId);
}

/** Builds the decided-customer-id set from a decision list, skipping null customerIds. */
export function decidedCustomerIds(decisions: Pick<DecisionView, "customerId">[]): Set<number> {
  const ids = new Set<number>();
  for (const d of decisions) {
    if (d.customerId != null) ids.add(d.customerId);
  }
  return ids;
}
