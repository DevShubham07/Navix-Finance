/**
 * Page-level checks for `/staff/accounting/transactions` (Phase 2): the period pills are the
 * console's square navy pills, a filter change on a later page asks the server once (for page 1),
 * the table says it is busy while the next page loads behind the old one, and a caption under the
 * stat cards names the period and the row count.
 *
 * Lives under `src/lib/staff` beside the helper it exercises; the page itself is imported by path.
 */
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { staffApi, type TransactionPage, type TransactionView } from "@/lib/api/applications";
import TransactionsPage from "@/app/staff/accounting/transactions/page";

function txn(id: number): TransactionView {
  return {
    id: `R-${id}`,
    type: "REPAYMENT",
    direction: "INCOMING",
    date: "2026-10-01",
    amountPaise: 100_000,
    borrowerName: `Borrower ${id}`,
    pan: null,
    txnRef: null,
    proofUrl: null,
    status: "VERIFIED",
    loanId: id,
    customerId: id,
  };
}

function pageOf(page: number, total = 60): TransactionPage {
  return {
    rows: [txn(page * 100 + 1), txn(page * 100 + 2)],
    page,
    size: 25,
    total,
    totalInPaise: 500_000,
    totalOutPaise: 200_000,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TransactionsPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  // `/me` — an accountant holds `loan:activate`, which the ledger's gate asks for.
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ session: { id: "4", name: "Acct", role: "ACCOUNTANT" } })),
  );
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("TransactionsPage", () => {
  it("renders the period as square navy pills with the selection announced", async () => {
    vi.spyOn(staffApi, "transactions").mockResolvedValue(pageOf(1));
    renderPage();

    const month = await screen.findByRole("button", { name: "This month" });
    expect(month).toHaveAttribute("aria-pressed", "true");
    expect(month).toHaveClass("bg-navy", "rounded");
    expect(month).not.toHaveClass("bg-gold");
    expect(month).not.toHaveClass("rounded-full");
    expect(screen.getByRole("button", { name: "Today" })).toHaveAttribute("aria-pressed", "false");
  });

  it("captions the stat cards with the period and the server's row count", async () => {
    vi.spyOn(staffApi, "transactions").mockResolvedValue(pageOf(1, 60));
    renderPage();

    expect(await screen.findByText("This month · 60 rows")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Transactions ledger" })).toBeInTheDocument();
  });

  it("asks once, for page 1, when the period changes on a later page", async () => {
    const spy = vi.spyOn(staffApi, "transactions").mockImplementation(async (_q, _d, _r, paging) => pageOf(paging?.page ?? 1));
    renderPage();

    await screen.findByText("Borrower 101");
    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    await screen.findByText("Borrower 201");
    expect(spy).toHaveBeenLastCalledWith(undefined, undefined, expect.anything(), { page: 2, size: 25 });

    const before = spy.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "All time" }));
    await screen.findByText("All time · 60 rows");

    const after = spy.mock.calls.slice(before);
    expect(after).toHaveLength(1);
    // "All time" sends no date window, and the page is back to 1 — never the stale page 2.
    expect(after[0]).toEqual([undefined, undefined, undefined, { page: 1, size: 25 }]);
  });

  it("asks once, for page 1, when the direction changes on a later page", async () => {
    const spy = vi.spyOn(staffApi, "transactions").mockImplementation(async (_q, _d, _r, paging) => pageOf(paging?.page ?? 1));
    renderPage();

    await screen.findByText("Borrower 101");
    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    await screen.findByText("Borrower 201");

    const before = spy.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "Incoming" }));
    await waitFor(() => expect(spy.mock.calls.length).toBeGreaterThan(before));
    await screen.findByText("Borrower 101");

    const after = spy.mock.calls.slice(before);
    expect(after).toHaveLength(1);
    expect(after[0][1]).toBe("INCOMING");
    expect(after[0][3]).toEqual({ page: 1, size: 25 });
  });

  it("announces the selected direction tab, not by colour alone", async () => {
    vi.spyOn(staffApi, "transactions").mockResolvedValue(pageOf(1));
    renderPage();

    const all = await screen.findByRole("button", { name: "All" });
    expect(all).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Incoming" })).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(screen.getByRole("button", { name: "Incoming" }));
    expect(screen.getByRole("button", { name: "Incoming" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "All" })).toHaveAttribute("aria-pressed", "false");
  });

  it("never captions the previous period's count with the new period's label", async () => {
    let release: (v: TransactionPage) => void = () => {};
    vi.spyOn(staffApi, "transactions").mockImplementation((_q, _d, range) =>
      range ? Promise.resolve(pageOf(1, 60)) : new Promise<TransactionPage>((r) => (release = r)),
    );
    renderPage();

    expect(await screen.findByText("This month · 60 rows")).toBeInTheDocument();

    // "All time" (no date window) is held open: the month's rows stay on screen behind it.
    fireEvent.click(screen.getByRole("button", { name: "All time" }));
    await waitFor(() =>
      expect(screen.getByRole("table", { name: "Transactions ledger" })).toHaveAttribute("aria-busy", "true"),
    );
    expect(screen.queryByText("All time · 60 rows")).not.toBeInTheDocument();
    expect(screen.queryByText("This month · 60 rows")).not.toBeInTheDocument();

    release(pageOf(1, 900));
    expect(await screen.findByText("All time · 900 rows")).toBeInTheDocument();
  });

  it("dims the table and marks it busy while the next page loads behind the current one", async () => {
    let release: (v: TransactionPage) => void = () => {};
    vi.spyOn(staffApi, "transactions").mockImplementation((_q, _d, _r, paging) =>
      (paging?.page ?? 1) === 1 ? Promise.resolve(pageOf(1)) : new Promise<TransactionPage>((r) => (release = r)),
    );
    renderPage();

    await screen.findByText("Borrower 101");
    const table = screen.getByRole("table", { name: "Transactions ledger" });
    expect(table).toHaveAttribute("aria-busy", "false");

    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    await waitFor(() => expect(screen.getByRole("table", { name: "Transactions ledger" })).toHaveAttribute("aria-busy", "true"));
    // The previous page stays readable underneath.
    expect(screen.getByText("Borrower 101")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Transactions ledger" }).parentElement).toHaveClass("opacity-60");

    release(pageOf(2));
    await screen.findByText("Borrower 201");
    expect(screen.getByRole("table", { name: "Transactions ledger" })).toHaveAttribute("aria-busy", "false");
    expect(screen.getByRole("table", { name: "Transactions ledger" }).parentElement).not.toHaveClass("opacity-60");
  });
});
