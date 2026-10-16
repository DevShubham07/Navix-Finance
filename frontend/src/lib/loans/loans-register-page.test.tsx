/**
 * Page-level checks for `/staff/loans` (Phase 2):
 *  - the DPD cell tells "due today" from "not due" (IST day), instead of "—" for both;
 *  - a segment chip or a date-window change starts again at page 1; picking "Custom" without
 *    applying a range changes no window, so it keeps the page;
 *  - the segment-chip counts hold their last values while a new date range loads, rather than
 *    flashing "(0)";
 *  - opening a row hands the pop-up the application id the row already carries, on the Loan tab;
 *  - a column filter narrows the register, reaches the export, survives a reload through the URL,
 *    and returns to page 1 in a single update (Phase 2: the register-query hook);
 *  - the register is server-paged: every view is one `loansApi.page` request, the chip counts are
 *    three one-row requests (unfiltered by the columns), and a saved filter set shows exactly the rows
 *    and count the old client-side matcher produced (the equivalence block at the bottom).
 *
 * Lives under `src/lib/loans` beside the helpers it exercises; the page itself is imported by path.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loansApi, type LoanRegisterRow, type RegisterPage } from "@/lib/api/applications";
import { matchesFilter, istDay } from "@/lib/staff/column-filter-match";
import { parseFilterValue } from "@/lib/staff/column-filter-params";
import { segmentOf } from "@/lib/loans/segments";
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

// Captures what the page hands the export menu. The real one renders nothing for a non-ADMIN, and
// what is under test here is the ROWS and the `getAllRows` the page passes, not who may click it.
type ExportProps = {
  rows: Array<{ borrowerName: string | null }>;
  scope?: string;
  getAllRows?: () => Promise<Array<{ borrowerName: string | null }>>;
};
const exportCalls: ExportProps[] = [];
vi.mock("@/components/staff/export-menu", () => ({
  ExportMenu: (props: ExportProps) => {
    exportCalls.push(props);
    return null;
  },
}));

/** Borrower names in the most recent set handed to the export menu. */
function exportedRows(): Array<string | null> {
  return (exportCalls.at(-1)?.rows ?? []).map((r) => r.borrowerName);
}

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

type PageArgs = Parameters<typeof loansApi.page>[0];

/** Every request the page made, so a test can say what travelled. */
let pageCalls: PageArgs[];
/** The loans the fake server holds. A test replaces it to change what the "database" contains. */
let dataset: LoanRegisterRow[];
/** While set, requests wait here instead of answering — how a test holds a view in flight. */
let held: Array<() => void> | null;

/**
 * The server, as the wire sees it: it knows ONLY the query string (`seg`, `q`, `f.*`, `from`/`to`,
 * `page`/`size`), never the page's column definitions. Deliberately written in the SQL's terms — the
 * stored enum, paise compared to a bound scaled by 100, a lower-cased `contains` — so that agreeing
 * with the old client matcher (see the equivalence block) means the two really are the same filter.
 */
function serve(p: PageArgs): RegisterPage<LoanRegisterRow> {
  const f = p.filters ?? {};
  const ex = p.extra ?? {};
  let out = dataset.filter((r) => (ex.seg ? segmentOf(r) === ex.seg : true));
  // The date window is the backend integration test's business; here only its params are asserted.
  if (f.q) out = out.filter((r) => (r.borrowerName ?? "").toLowerCase().includes(f.q.toLowerCase()) || String(r.loanId).includes(f.q));
  for (const [key, raw] of Object.entries(f)) {
    if (!key.startsWith("f.")) continue;
    const filter = parseFilterValue(raw);
    if (!filter) continue;
    const col = key.slice(2);
    if (col === "status" && filter.type === "in") out = out.filter((r) => filter.values.includes(r.status));
    if (col === "principalPaise" && filter.type === "num") {
      out = out.filter(
        (r) =>
          (filter.min == null || r.principalPaise >= Math.round(filter.min * 100)) &&
          (filter.max == null || r.principalPaise <= Math.round(filter.max * 100)),
      );
    }
    if (col === "borrowerName" && filter.type === "text") {
      out = out.filter((r) => (r.borrowerName ?? "").toLowerCase().includes(filter.q.trim().toLowerCase()));
    }
  }
  const start = (p.page - 1) * p.size;
  return { rows: out.slice(start, start + p.size), page: p.page, size: p.size, total: out.length };
}

beforeEach(() => {
  replace.mockClear();
  exportCalls.length = 0;
  // The register remembers a staffer's last view, so without this each test would inherit the
  // filters the previous one left behind — which is the real behaviour, and useless in a test.
  localStorage.clear();
  window.history.replaceState({}, "", "/staff/loans");
  pageCalls = [];
  dataset = SIXTY;
  held = null;
  // `useStaffMe` asks the BFF who is signed in; COLLECTION_HEAD holds `loan:register`.
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ session: { id: "5", name: "Head", role: "COLLECTION_HEAD" } })),
  );
  vi.spyOn(loansApi, "page").mockImplementation((p) => {
    pageCalls.push(p);
    if (held) return new Promise((resolve) => held!.push(() => resolve(serve(p))));
    return Promise.resolve(serve(p));
  });
  vi.spyOn(loansApi, "facet").mockImplementation(async (column) => ({
    values: column === "status" ? ["ACTIVE", "CLOSED"] : [],
    truncated: false,
  }));
});

/** Requests for the TABLE (the chip counts are the one-row requests with a `seg`). */
function tableCalls() {
  return pageCalls.filter((c) => c.size !== 1);
}

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
    dataset = [
      row({ loanId: 1, borrowerName: "Due Today", dueDate: today }),
      row({ loanId: 2, borrowerName: "Not Yet Due", dueDate: "2099-01-01" }),
      row({ loanId: 3, borrowerName: "Running Late", dueDate: "2026-01-01", dpd: 4, status: "OVERDUE" }),
      row({ loanId: 4, borrowerName: "Paid Off", dueDate: today, closedOn: today, status: "CLOSED" }),
    ];
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

  it("asks the server for ONE page, never the whole window", async () => {
    renderPage();
    await screen.findByText("Borrower 1");

    expect(screen.queryByText("Borrower 26")).not.toBeInTheDocument();
    expect(tableCalls()[0]).toMatchObject({ page: 1, size: 25, filters: { sort: "dueDate", dir: "desc" } });
    expect(loansApi.facet).not.toHaveBeenCalled(); // facets load on panel open, never on mount
  });

  it("restarts at page 1 on a segment change", async () => {
    renderPage();
    await goToPage2();

    // Still sixty rows in "Active", so only the handler — not the hook's clamp — can reset the page.
    fireEvent.click(screen.getByRole("button", { name: /^Active \(60\)$/ }));
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    expect(window.location.search).toBe("?seg=active");
    expect(tableCalls().at(-1)?.extra).toMatchObject({ seg: "active" });
  });

  it("restarts at page 1 when the date window changes", async () => {
    renderPage();
    await goToPage2();

    fireEvent.click(screen.getByRole("button", { name: "Today" }));
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    await waitFor(() => expect(pageCalls.some((c) => c.extra?.from != null)).toBe(true));
  });

  it("keeps the page when picking Custom has not applied a window yet", async () => {
    renderPage();
    await goToPage2();

    fireEvent.click(screen.getByRole("button", { name: "Custom" }));
    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
  });

  it("keeps the chip counts unfiltered by the column filters", async () => {
    dataset = [
      row({ loanId: 1, borrowerName: "Asha Verma", status: "ACTIVE" }),
      row({ loanId: 2, borrowerName: "Bela Rao", status: "CLOSED", closedOn: "2026-09-20" }),
      row({ loanId: 3, borrowerName: "Chetan Das", status: "OVERDUE", dpd: 3 }),
    ];
    window.history.replaceState({}, "", "/staff/loans?f.status=in%3ACLOSED");
    renderPage();
    await screen.findByText("Bela Rao");

    // The table is narrowed to one closed loan; the chips still count the whole book.
    expect(screen.getByRole("button", { name: /^All \(3\)$/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Active \(1\)$/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Overdue \(1\)$/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Closed \(1\)$/ })).toBeInTheDocument();
    // ...and no count request carried a column filter.
    for (const c of pageCalls.filter((c) => c.size === 1)) {
      expect(Object.keys(c.filters ?? {}).filter((k) => k.startsWith("f."))).toEqual([]);
    }
  });

  it("holds the segment counts while a new date window loads", async () => {
    renderPage();
    await screen.findByRole("button", { name: /^All \(60\)$/ });

    // The next window never answers: the chips must keep the last counts, not drop to (0).
    held = [];
    fireEvent.click(screen.getByRole("button", { name: "Yesterday" }));

    await waitFor(() => expect(pageCalls.some((c) => c.extra?.from != null)).toBe(true));
    expect(screen.getByRole("button", { name: /^All \(60\)$/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Active \(60\)$/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^All \(0\)$/ })).not.toBeInTheDocument();
  });

  it("marks the held rows busy while the next window loads, then clears it", async () => {
    renderPage();
    await screen.findByText("Borrower 1");
    expect(screen.getByRole("table", { name: "Loans register" })).toHaveAttribute("aria-busy", "false");

    held = [];
    fireEvent.click(screen.getByRole("button", { name: "Yesterday" }));
    await waitFor(() =>
      expect(screen.getByRole("table", { name: "Loans register" })).toHaveAttribute("aria-busy", "true"),
    );

    dataset = SIXTY.slice(0, 2);
    const release = held;
    held = null;
    release.forEach((go) => go());
    await waitFor(() =>
      expect(screen.getByRole("table", { name: "Loans register" })).toHaveAttribute("aria-busy", "false"),
    );
    expect(screen.getByRole("button", { name: /^All \(2\)$/ })).toBeInTheDocument();
  });

  it("does not claim 'No loans' for a new search before its response arrives", async () => {
    dataset = [];
    renderPage();
    expect(await screen.findByText("No loans.")).toBeInTheDocument();

    held = [];
    fireEvent.change(screen.getByLabelText("Search loans"), { target: { value: "asha" } });
    fireEvent.submit(screen.getByLabelText("Search loans").closest("form") as HTMLFormElement);

    await waitFor(() =>
      expect(loansApi.page).toHaveBeenCalledWith(expect.objectContaining({ filters: expect.objectContaining({ q: "asha" }) })),
    );
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

  it("honours a ?seg= deep link", async () => {
    dataset = [row({ loanId: 1, borrowerName: "Live One" }), row({ loanId: 2, borrowerName: "Late One", status: "OVERDUE", dpd: 2 })];
    window.history.replaceState({}, "", "/staff/loans?seg=overdue");
    renderPage();

    expect(await screen.findByText("Late One")).toBeInTheDocument();
    expect(screen.queryByText("Live One")).not.toBeInTheDocument();
  });

  describe("column filters", () => {
    /** Open a header's filter panel by the column's name. */
    function openFilter(column: string) {
      fireEvent.click(screen.getByRole("button", { name: new RegExp(`^Filter ${column}`) }));
      return screen.getByRole("dialog", { name: new RegExp(`Filter and sort ${column}`) });
    }

    const TWO = () => [
      row({ loanId: 1, borrowerName: "Asha Verma", status: "ACTIVE" }),
      row({ loanId: 2, borrowerName: "Bela Rao", status: "CLOSED", closedOn: "2026-09-20" }),
    ];

    it("narrows the register and says so in the URL", async () => {
      dataset = TWO();
      renderPage();
      await screen.findByText("Asha Verma");

      const panel = openFilter("Status");
      // The checklist comes from the facets endpoint, opened by the panel — not from the rows.
      // The checklist opens before its facet has arrived, so nothing is pre-ticked: pick the one wanted.
      // Everything starts ticked once the facet lands (Excel semantics), so clear the lot and tick
      // back only the one wanted — clicking it directly would UNtick it.
      fireEvent.click(await within(panel).findByLabelText("(Select All)"));
      fireEvent.click(within(panel).getByLabelText("Active"));
      fireEvent.click(within(panel).getByRole("button", { name: "Apply" }));

      await waitFor(() => expect(screen.queryByText("Bela Rao")).not.toBeInTheDocument());
      expect(screen.getByText("Asha Verma")).toBeInTheDocument();
      expect(loansApi.facet).toHaveBeenCalledWith("status", expect.any(Object));
      // The stored enum travels, not the word "Active" the cell shows.
      expect(replace.mock.calls.at(-1)?.[0]).toContain("f.status=in%3AACTIVE");
    });

    it("restores a filtered view from the URL", async () => {
      window.history.replaceState({}, "", "/staff/loans?f.status=in%3ACLOSED");
      dataset = TWO();
      renderPage();

      expect(await screen.findByText("Bela Rao")).toBeInTheDocument();
      expect(screen.queryByText("Asha Verma")).not.toBeInTheDocument();
      expect(tableCalls()[0].filters).toMatchObject({ "f.status": "in:CLOSED" });
    });

    it("bounds a money column in rupees, not paise", async () => {
      dataset = [
        row({ loanId: 1, borrowerName: "Small", principalPaise: 500_000 }),   // ₹5,000
        row({ loanId: 2, borrowerName: "Large", principalPaise: 2_500_000 }), // ₹25,000
      ];
      renderPage();
      await screen.findByText("Small");

      const panel = openFilter("Principal");
      fireEvent.change(within(panel).getByLabelText("Minimum"), { target: { value: "10000" } });
      fireEvent.click(within(panel).getByRole("button", { name: "Apply" }));

      await waitFor(() => expect(screen.queryByText("Small")).not.toBeInTheDocument());
      expect(screen.getByText("Large")).toBeInTheDocument();
      // Rupees on the wire; the server scales the operand to paise.
      expect(tableCalls().at(-1)?.filters).toMatchObject({ "f.principalPaise": "num:10000.." });
    });

    it("returns to page 1 when a filter lands on a later page", async () => {
      renderPage();
      await screen.findByText("Borrower 1");

      fireEvent.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Borrower 26");

      const panel = openFilter("Borrower");
      fireEvent.change(within(panel).getByLabelText("Borrower contains"), { target: { value: "Borrower 1" } });
      fireEvent.click(within(panel).getByRole("button", { name: "Apply" }));

      // Back at the top of the narrowed set, not stranded on page 2 of the old one.
      expect(await screen.findByText("Borrower 1")).toBeInTheDocument();
      expect(screen.getByText(/Page 1 of/)).toBeInTheDocument();
      expect(tableCalls().at(-1)?.page).toBe(1);
    });

    it("hands the export this page, and a getAllRows that re-asks under the same filters", async () => {
      dataset = TWO();
      renderPage();
      await screen.findByText("Asha Verma");

      const panel = openFilter("Status");
      // The checklist opens before its facet has arrived, so nothing is pre-ticked: pick the one wanted.
      // Everything starts ticked once the facet lands (Excel semantics), so clear the lot and tick
      // back only the one wanted — clicking it directly would UNtick it.
      fireEvent.click(await within(panel).findByLabelText("(Select All)"));
      fireEvent.click(within(panel).getByLabelText("Active"));
      fireEvent.click(within(panel).getByRole("button", { name: "Apply" }));
      await waitFor(() => expect(screen.queryByText("Bela Rao")).not.toBeInTheDocument());

      expect(exportedRows()).toEqual(["Asha Verma"]);
      expect(exportCalls.at(-1)?.scope).toBe("page");
      const all = await exportCalls.at(-1)!.getAllRows!();
      expect(all.map((r) => r.borrowerName)).toEqual(["Asha Verma"]);
      expect(pageCalls.at(-1)?.filters).toMatchObject({ "f.status": "in:ACTIVE" });
    });
  });

  // The move from "fetch everything, narrow in the browser" to "ask the server for a page" must show
  // the same rows and the same count. The oracle is the OLD client path: the real matcher over the
  // column definitions the page used to declare (rupees for money, the stored enum for status, the
  // label for text). The page under test gets its rows from `serve`, which knows only the wire.
  describe("equivalence with the old client-side filtering", () => {
    const FIXTURE = [
      row({ loanId: 1, borrowerName: "Asha Verma", status: "ACTIVE", principalPaise: 500_000, disbursedOn: "2026-09-02" }),
      row({ loanId: 2, borrowerName: "Bela Rao", status: "CLOSED", principalPaise: 2_500_000, closedOn: "2026-09-20" }),
      row({ loanId: 3, borrowerName: "Chetan Das", status: "OVERDUE", principalPaise: 1_500_000, dpd: 5 }),
      row({ loanId: 4, borrowerName: "Dev Anand", status: "OVERDUE", principalPaise: 3_000_000, dpd: 9 }),
      row({ loanId: 5, borrowerName: "Esha Nair", status: "ACTIVE", principalPaise: 5_000_000 }),
      row({ loanId: 6, borrowerName: "Farid", status: "CLOSED", principalPaise: 900_000, closedOn: "2026-09-01" }),
    ];
    // Saved view: status in (OVERDUE, CLOSED) AND principal >= Rs 10,000 AND borrower contains "a".
    const SAVED = "f.status=in%3AOVERDUE%2CCLOSED&f.principalPaise=num%3A10000..&f.borrowerName=text%3Acontains%3Aa";

    function oldClientRows(): string[] {
      const view = new URLSearchParams(SAVED.replace(/%3A/g, ":").replace(/%2C/g, ","));
      return FIXTURE.filter((l) => {
        const cells = {
          status: { raw: l.status, text: l.status },
          principalPaise: { raw: "", text: "", num: l.principalPaise / 100 },
          borrowerName: { raw: "", text: l.borrowerName ?? "" },
        } as const;
        return Object.entries(cells).every(([col, cell]) => {
          const f = view.get(`f.${col}`);
          const parsed = f ? parseFilterValue(f) : undefined;
          return !parsed || matchesFilter(parsed, cell);
        });
      }).map((l) => l.borrowerName ?? "");
    }

    it("shows exactly the rows and the count the client matcher produced", async () => {
      const expected = oldClientRows();
      expect(expected).toEqual(["Bela Rao", "Chetan Das", "Dev Anand"]); // the oracle itself is not trivial
      dataset = FIXTURE;
      window.history.replaceState({}, "", `/staff/loans?${SAVED}`);
      renderPage();

      await screen.findByText(expected[0]);
      const shown = within(screen.getByRole("table", { name: "Loans register" }))
        .getAllByRole("row")
        .map((r) => FIXTURE.find((l) => l.borrowerName && within(r).queryByText(l.borrowerName))?.borrowerName)
        .filter(Boolean);
      expect(shown).toEqual(expected);
      expect(screen.getByText(new RegExp(`of ${expected.length} loans`))).toBeInTheDocument();
      expect(exportedRows()).toEqual(expected);
      expect(istDay("2026-09-02")).toBe("2026-09-02"); // keeps the matcher import honest
    });
  });

  it("Clear filters drops the column filters but keeps the search term", async () => {
    // The bar's onClear is setFilters({}), not clearAll(): the latter would also wipe the search box.
    window.history.replaceState({}, "", "/staff/loans?q=asha&f.status=in%3ACLOSED");
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Clear filters" }));

    await waitFor(() => expect(pageCalls.at(-1)?.filters ?? pageCalls.at(-1)).not.toHaveProperty("f.status"));
    const url = new URLSearchParams(window.location.search);
    expect(url.get("q")).toBe("asha");
    expect(url.has("f.status")).toBe(false);
  });
});
