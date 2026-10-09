/**
 * Page-level checks for `/staff/loans` (Phase 2):
 *  - the DPD cell tells "due today" from "not due" (IST day), instead of "—" for both;
 *  - a segment chip or a date-window change starts again at page 1; picking "Custom" without
 *    applying a range changes no window, so it keeps the page;
 *  - the segment-chip counts hold their last values while a new date range loads, rather than
 *    flashing "(0)";
 *  - opening a row hands the pop-up the application id the row already carries, on the Loan tab.
 *
 * Lives under `src/lib/loans` beside the helpers it exercises; the page itself is imported by path.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loansApi, type LoanRegisterRow } from "@/lib/api/applications";
import { iso } from "@/lib/period";
import { istCalendarToday } from "@/lib/customers/customer-360";
// `vi.mock` calls below are hoisted above every import, so the page sees the mocked modules.
import LoansPage from "@/app/staff/loans/page";

const replace = vi.fn((url: string) => window.history.replaceState({}, "", url));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/loans",
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

// The real dialog fetches whole files; here it only has to prove what it was opened with.
vi.mock("@/components/staff/application-detail-dialog", () => ({
  ApplicationDetailDialog: ({
    loanId,
    applicationId,
    initialTab,
  }: {
    loanId?: number | null;
    applicationId: number | null;
    initialTab?: string;
  }) =>
    loanId == null ? null : (
      <div role="dialog">
        Loan {loanId} application={applicationId ?? "none"} tab={initialTab}
      </div>
    ),
}));

function row(overrides: Partial<LoanRegisterRow> = {}): LoanRegisterRow {
  return {
    loanId: 1,
    customerId: 101,
    applicationId: 201,
    borrowerName: "Asha Verma",
    mobile: "9876543210",
    pan: "ABCDE1234F",
    loanCycle: 1,
    principalPaise: 1_000_000,
    netDisbursedPaise: 882_000,
    totalRepayablePaise: 1_270_000,
    outstandingPaise: 1_270_000,
    sanctionedAmountPaise: 1_000_000,
    sanctionedAt: "2026-09-01",
    disbursedOn: "2026-09-02",
    dueDate: "2099-01-01",
    closedOn: null,
    salaryCreditDay: 30,
    status: "ACTIVE",
    dpd: 0,
    assignedOfficerId: null,
    assignedOfficerName: null,
    disbursalTxnRef: null,
    ...overrides,
  } as LoanRegisterRow;
}

/** Sixty live loans — three pages at the default 25 — all in the "Active" segment. */
const SIXTY = Array.from({ length: 60 }, (_, i) =>
  row({ loanId: i + 1, customerId: 100 + i + 1, applicationId: 200 + i + 1, borrowerName: `Borrower ${i + 1}` }),
);

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <LoansPage />
    </QueryClientProvider>,
  );
}

let listCalls: { from?: string; to?: string }[];

beforeEach(() => {
  replace.mockClear();
  window.history.replaceState({}, "", "/staff/loans");
  listCalls = [];
  // `useStaffMe` asks the BFF who is signed in; COLLECTION_HEAD holds `loan:register`.
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ session: { id: "5", name: "Head", role: "COLLECTION_HEAD" } })),
  );
  vi.spyOn(loansApi, "list").mockImplementation(async (_q, range) => {
    listCalls.push(range ?? {});
    return SIXTY;
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

function rowFor(name: string): HTMLElement {
  return screen.getByText(name).closest("tr") as HTMLElement;
}

async function goToPage2() {
  fireEvent.click(await screen.findByRole("button", { name: /Next/ }));
  expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
}

describe("LoansPage", () => {
  it("labels DPD as due today / not due / days late / — by the IST day", async () => {
    const today = iso(istCalendarToday());
    vi.mocked(loansApi.list).mockResolvedValue([
      row({ loanId: 1, borrowerName: "Due Today", dueDate: today }),
      row({ loanId: 2, borrowerName: "Not Yet Due", dueDate: "2099-01-01" }),
      row({ loanId: 3, borrowerName: "Running Late", dueDate: "2026-01-01", dpd: 4, status: "OVERDUE" }),
      row({ loanId: 4, borrowerName: "Paid Off", dueDate: today, closedOn: today, status: "CLOSED" }),
    ]);
    renderPage();
    await screen.findByText("Due Today");

    expect(within(rowFor("Due Today")).getByText("due today")).toBeInTheDocument();
    expect(within(rowFor("Not Yet Due")).getByText("not due")).toBeInTheDocument();
    expect(within(rowFor("Running Late")).getByText("4d")).toBeInTheDocument();
    expect(within(rowFor("Paid Off")).queryByText("due today")).not.toBeInTheDocument();
    expect(within(rowFor("Paid Off")).getByText("—")).toBeInTheDocument();
  });

  it("names the register and scopes every column header", async () => {
    renderPage();
    await screen.findByText("Borrower 1");

    expect(screen.getByRole("table", { name: "Loans register" })).toBeInTheDocument();
    for (const th of screen.getAllByRole("columnheader")) expect(th).toHaveAttribute("scope", "col");
  });

  it("restarts at page 1 on a segment change", async () => {
    renderPage();
    await goToPage2();

    // Still sixty rows in "Active", so only the handler — not the hook's clamp — can reset the page.
    fireEvent.click(screen.getByRole("button", { name: /^Active \(60\)$/ }));
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    expect(window.location.search).toBe("?seg=active");
  });

  it("restarts at page 1 when the date window changes", async () => {
    renderPage();
    await goToPage2();

    fireEvent.click(screen.getByRole("button", { name: "Today" }));
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    await waitFor(() => expect(listCalls.some((r) => r.from != null)).toBe(true));
  });

  it("keeps the page when picking Custom has not applied a window yet", async () => {
    renderPage();
    await goToPage2();

    fireEvent.click(screen.getByRole("button", { name: "Custom" }));
    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
  });

  it("holds the segment counts while a new date window loads", async () => {
    renderPage();
    await screen.findByRole("button", { name: /^All \(60\)$/ });

    // The next window never answers: the chips must keep the last counts, not drop to (0).
    vi.mocked(loansApi.list).mockImplementation(() => new Promise<LoanRegisterRow[]>(() => {}));
    fireEvent.click(screen.getByRole("button", { name: "Yesterday" }));

    await waitFor(() => expect(loansApi.list).toHaveBeenCalledTimes(2));
    expect(screen.getByRole("button", { name: /^All \(60\)$/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Active \(60\)$/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^All \(0\)$/ })).not.toBeInTheDocument();
  });

  it("marks the held rows busy while the next window loads, then clears it", async () => {
    renderPage();
    await screen.findByText("Borrower 1");
    expect(screen.getByRole("table", { name: "Loans register" })).toHaveAttribute("aria-busy", "false");

    let answer: (rows: LoanRegisterRow[]) => void = () => {};
    vi.mocked(loansApi.list).mockImplementation(() => new Promise<LoanRegisterRow[]>((r) => (answer = r)));
    fireEvent.click(screen.getByRole("button", { name: "Yesterday" }));

    await waitFor(() =>
      expect(screen.getByRole("table", { name: "Loans register" })).toHaveAttribute("aria-busy", "true"),
    );
    answer(SIXTY.slice(0, 2));
    await waitFor(() =>
      expect(screen.getByRole("table", { name: "Loans register" })).toHaveAttribute("aria-busy", "false"),
    );
    expect(screen.getByRole("button", { name: /^All \(2\)$/ })).toBeInTheDocument();
  });

  it("does not claim 'No loans' for a new search before its response arrives", async () => {
    vi.mocked(loansApi.list).mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText("No loans.")).toBeInTheDocument();

    vi.mocked(loansApi.list).mockImplementation(() => new Promise<LoanRegisterRow[]>(() => {}));
    fireEvent.change(screen.getByLabelText("Search loans"), { target: { value: "asha" } });
    fireEvent.submit(screen.getByLabelText("Search loans").closest("form") as HTMLFormElement);

    await waitFor(() => expect(loansApi.list).toHaveBeenCalledWith("asha", expect.anything()));
    expect(screen.queryByText(/No loans for/)).not.toBeInTheDocument();
    expect(screen.queryByText("No loans.")).not.toBeInTheDocument();
  });

  it("opens the pop-up on the Loan tab with the row's own application id", async () => {
    renderPage();
    await screen.findByText("Borrower 3");

    fireEvent.click(within(rowFor("Borrower 3")).getByRole("button", { name: "#3" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("Loan 3 application=203 tab=loan");
  });

  it("opens a ?open= deep link on the loan id alone", async () => {
    window.history.replaceState({}, "", "/staff/loans?q=7&open=7");
    renderPage();

    expect(await screen.findByRole("dialog")).toHaveTextContent("Loan 7 application=none tab=loan");
  });
});
