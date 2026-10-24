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
// `vi.mock` is hoisted above every import, so the page sees the mocked router.
import TransactionsPage from "@/app/staff/accounting/transactions/page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/accounting/transactions",
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

type PageArgs = Parameters<typeof staffApi.transactionsPage>[0];
/** The ledger's page endpoint answers with the sums beside the register page's rows/total. */
type Ledger = Awaited<ReturnType<typeof staffApi.transactionsPage>>;
const ledgerPage = (p: TransactionPage) => p as unknown as Ledger;

function txn(id: number, over: Partial<TransactionView> = {}): TransactionView {
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
    repaymentDate: "2026-10-05",
    ...over,
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
    pendingInPaise: 0,
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
  replace.mockReset();
  window.history.replaceState({}, "", "/staff/accounting/transactions");
  try {
    window.localStorage.clear(); // the register remembers its view per staffer
  } catch {
    /* storage unavailable: the page must render without it */
  }
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
    vi.spyOn(staffApi, "transactionsPage").mockResolvedValue(ledgerPage(pageOf(1)));
    renderPage();

    const month = await screen.findByRole("button", { name: "This month" });
    expect(month).toHaveAttribute("aria-pressed", "true");
    expect(month).toHaveClass("bg-navy", "rounded");
    expect(month).not.toHaveClass("bg-gold");
    expect(month).not.toHaveClass("rounded-full");
    expect(screen.getByRole("button", { name: "Today" })).toHaveAttribute("aria-pressed", "false");
  });

  it("captions the stat cards with the period and the server's row count", async () => {
    vi.spyOn(staffApi, "transactionsPage").mockResolvedValue(ledgerPage(pageOf(1, 60)));
    renderPage();

    expect(await screen.findByText("This month · 60 rows")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Transactions ledger" })).toBeInTheDocument();
  });

  it("asks once, for page 1, when the period changes on a later page", async () => {
    const spy = vi.spyOn(staffApi, "transactionsPage").mockImplementation(async (p: PageArgs) => ledgerPage(pageOf(p.page)));
    renderPage();

    await screen.findByText("Borrower 101");
    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    await screen.findByText("Borrower 201");
    expect(spy.mock.lastCall?.[0]).toMatchObject({ page: 2, size: 25, extra: { from: expect.any(String), to: expect.any(String) } });

    const before = spy.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "All time" }));
    await screen.findByText("All time · 60 rows");

    const after = spy.mock.calls.slice(before);
    expect(after).toHaveLength(1);
    // "All time" sends no date window, and the page is back to 1 — never the stale page 2.
    expect(after[0][0].page).toBe(1);
    expect(after[0][0].extra).toEqual({});
  });

  it("asks once, for page 1, when the direction changes on a later page", async () => {
    const spy = vi.spyOn(staffApi, "transactionsPage").mockImplementation(async (p: PageArgs) => ledgerPage(pageOf(p.page)));
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
    expect(after[0][0].extra?.direction).toBe("INCOMING");
    expect(after[0][0].page).toBe(1);
  });

  it("announces the selected direction tab, not by colour alone", async () => {
    vi.spyOn(staffApi, "transactionsPage").mockResolvedValue(ledgerPage(pageOf(1)));
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
    vi.spyOn(staffApi, "transactionsPage").mockImplementation((p: PageArgs) =>
      p.extra?.from ? Promise.resolve(ledgerPage(pageOf(1, 60))) : new Promise<Ledger>((r) => (release = r as unknown as typeof release)),
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

    release(ledgerPage(pageOf(1, 900)) as unknown as TransactionPage);
    expect(await screen.findByText("All time · 900 rows")).toBeInTheDocument();
  });

  it("dims the table and marks it busy while the next page loads behind the current one", async () => {
    let release: (v: TransactionPage) => void = () => {};
    vi.spyOn(staffApi, "transactionsPage").mockImplementation((p: PageArgs) =>
      p.page === 1 ? Promise.resolve(ledgerPage(pageOf(1))) : new Promise<Ledger>((r) => (release = r as unknown as typeof release)),
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

  it("shows the loan's repayment date, red when the payment came in after it and the grace day", async () => {
    vi.spyOn(staffApi, "transactionsPage").mockResolvedValue(ledgerPage({
      ...pageOf(1),
      rows: [
        txn(1, { date: "2026-10-01", repaymentDate: "2026-10-05" }),
        txn(2, { date: "2026-10-09", repaymentDate: "2026-10-05", borrowerName: "Late Payer" }),
        // The grace day itself: on time on a legacy loan (grace 1), late on a loan written without grace.
        txn(3, { date: "2026-10-06", repaymentDate: "2026-10-05" }),
        txn(4, { date: "2026-10-06", repaymentDate: "2026-10-05", graceDays: 0 }),
      ],
    }));
    renderPage();

    expect(await screen.findByRole("columnheader", { name: /Repayment date/ })).toBeInTheDocument();
    const cells = screen.getAllByTitle("Paid after the repayment date");
    expect(cells).toHaveLength(2);
    expect(cells[0]).toHaveClass("text-error-700");
  });

  it("opens the customer pop-up from the borrower's name", async () => {
    vi.spyOn(staffApi, "transactionsPage").mockResolvedValue(ledgerPage(pageOf(1)));
    const loan = vi.spyOn(staffApi, "loan").mockReturnValue(new Promise(() => {}));
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Open Borrower 101" }));
    await waitFor(() => expect(loan).toHaveBeenCalledWith(101));
  });

  it("labels inflow as verified money and shows what is still awaiting verification", async () => {
    vi.spyOn(staffApi, "transactionsPage").mockResolvedValue(ledgerPage({ ...pageOf(1), pendingInPaise: 250_000 }));
    renderPage();
    expect(await screen.findByText("Verified · ₹2,500 awaiting verification")).toBeInTheDocument();
  });

  it("shows the server's totals for the whole filter, and they do not move when the page does", async () => {
    vi.spyOn(staffApi, "transactionsPage").mockImplementation(async (p: PageArgs) =>
      ledgerPage({ ...pageOf(p.page), totalInPaise: 700_000, totalOutPaise: 300_000, pendingInPaise: 0 }),
    );
    renderPage();

    expect(await screen.findByText("₹7,000")).toBeInTheDocument(); // inflow
    expect(screen.getByText("₹3,000")).toBeInTheDocument();        // outflow
    expect(screen.getByText("+₹4,000")).toBeInTheDocument();       // net

    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    await screen.findByText("Borrower 201");
    expect(screen.getByText("₹7,000")).toBeInTheDocument();
    expect(screen.getByText("₹3,000")).toBeInTheDocument();
  });

  it("sends the view to the server: column sort, search, and no facet until a panel opens", async () => {
    const spy = vi.spyOn(staffApi, "transactionsPage").mockImplementation(async (p: PageArgs) => ledgerPage(pageOf(p.page)));
    const facet = vi.spyOn(staffApi, "transactionsFacet").mockResolvedValue({ values: ["DISBURSAL", "REPAYMENT"], truncated: false });
    renderPage();
    await screen.findByText("Borrower 101");
    expect(facet).not.toHaveBeenCalled(); // facets on panel open, never on mount
    expect(spy.mock.calls[0][0].filters).toMatchObject({ sort: "date", dir: "desc" });

    fireEvent.click(screen.getByRole("button", { name: "Sort by Amount" }));
    await waitFor(() => expect(spy.mock.lastCall?.[0].filters).toMatchObject({ sort: "amount" }));
    expect(spy.mock.lastCall?.[0].page).toBe(1);

    // Opening the (two-value) Type checklist is what asks for its facet, under the ledger's own params.
    fireEvent.click(screen.getByRole("button", { name: "Filter Type" }));
    await waitFor(() => expect(facet).toHaveBeenCalledWith("type", expect.objectContaining({ sort: "amount" })));
  });
});
