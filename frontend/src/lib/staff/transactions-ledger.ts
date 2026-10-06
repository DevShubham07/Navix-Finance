/**
 * Pure helpers for the accountant's transactions ledger (`/staff/accounting/transactions`).
 */

/**
 * The caption under the ledger's stat cards: "This month · 1,284 rows".
 *
 * The cards total the whole filtered period while the table shows one page of it; naming the period
 * and the server's row count says which of the two the cards describe. `total` is the server's
 * count for the current filter, grouped the Indian way (1,00,000) like every other figure on the
 * page.
 */
export function ledgerRowsCaption(periodLabel: string, total: number): string {
  const count = Math.max(0, Math.trunc(total));
  return `${periodLabel} · ${count.toLocaleString("en-IN")} ${count === 1 ? "row" : "rows"}`;
}
