/** Canonical staff route for a customer's full profile. */
export function customerPageHref(customerId: number): string {
  return `/staff/customers/${customerId}`;
}
