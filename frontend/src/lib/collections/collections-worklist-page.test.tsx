/**
 * `/staff/collections` (Phase 2): the DPD cell counts IST days to a not-yet-due date, overdue rows
 * carry a row-level cue on the leading cell, the bulk bar names its page scope, and reopening the
 * export menu does not refetch the customer enrichment.
 *
 * Lives under `src/lib/collections` beside `worklist-due.ts`; the page is imported by path.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { collectionsApi, customersApi, type LoanSummary, type WorklistRow } from "@/lib/api/applications";
import CollectionsBucketPage from "@/app/staff/collections/page";
import { exportCsv } from "@/lib/export/exporters";

// The CSV writer touches the DOM (Blob + anchor); the export tests only need to see what it was handed.
vi.mock("@/lib/export/exporters", async (orig) => ({
  ...(await orig<typeof import("@/lib/export/exporters")>()),
  exportCsv: vi.fn(),
}));

let search = "";
// Hoisted rather than fresh per render, so a test can assert on what the page navigated TO.
const push = vi.fn();
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push, refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/collections",
  useSearchParams: () => new URLSearchParams(search),
}));

const ADMIN = { id: "1", name: "Admin", role: "ADMIN" };

function loan(loanId: number, dueDate: string, preDue: boolean): LoanSummary {
  return {
    loanId,
    customerId: 500 + loanId,
    applicationId: null,
    status: "ACTIVE",
    principalPaise: 1_000_000,
    netDisbursedPaise: 882_000,
    totalRepayablePaise: 1_270_000,
    outstandingPaise: 1_270_000,
    disbursedOn: "2026-09-10",
    dueDate,
    borrowerName: `Borrower ${loanId}`,
    pan: null,
    mobile: null,
    employer: null,
    employmentStatus: null,
    monthlySalaryPaise: null,
    salaryBank: null,
    preDue,
  };
}

function row(loanId: number, dpd: number, bucket: WorklistRow["bucket"], dueDate: string, preDue: boolean): WorklistRow {
  return {
    loanId,
    dpd,
    bucket,
    preDue,
    caseId: null,
    assignedOfficerId: null,
    assignedOfficerName: null,
    caseOpenedAt: null,
    creditDecidedByName: null,
    disbursedByName: null,
    loan: loan(loanId, dueDate, preDue),
  };
}

const ROWS: WorklistRow[] = [
  row(10, 0, "UPCOMING", "2026-10-11", true),
  row(11, 0, "UPCOMING", "2026-10-06", false),
  row(12, 3, "T0_T7", "2026-10-03", false),
];

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CollectionsBucketPage />
    </QueryClientProvider>,
  );
}

function bodyRow(loanId: number) {
  return screen.getByRole("checkbox", { name: `Select loan #${loanId}` }).closest("tr") as HTMLTableRowElement;
}

beforeEach(() => {
  localStorage.setItem("navix-staff-working-role:1", "COLLECTION_HEAD"); // ADMIN defaults to ADMIN
  // 2026-10-06 00:30 IST — still the 5th in UTC. Only `Date` is faked; timers and promises are real.
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-05T19:00:00Z"));
  search = "bucket=UPCOMING";
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session: ADMIN })));
  // The cards read the bucket summary; the register reads a server page. The fake server applies the
  // one view param these tests vary (`bucket`) the way the real one does.
  vi.spyOn(collectionsApi, "worklist").mockResolvedValue(ROWS);
  vi.spyOn(collectionsApi, "worklistSummary").mockResolvedValue([
    { bucket: "UPCOMING", n: 2, outstandingPaise: 2_540_000 },
    { bucket: "T0_T7", n: 1, outstandingPaise: 1_270_000 },
  ]);
  vi.spyOn(collectionsApi, "worklistPage").mockImplementation(async (p) => {
    const rows = ROWS.filter((r) => !p.extra?.bucket || r.bucket === p.extra.bucket);
    return { rows, page: p.page, size: p.size, total: rows.length };
  });
  vi.spyOn(collectionsApi, "worklistFacet").mockResolvedValue({ values: [], truncated: false });
  vi.spyOn(collectionsApi, "listOfficers").mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("CollectionsBucketPage", () => {
  it("keeps the rest of the view when switching bucket", async () => {
    // A bucket switch used to rebuild the query string from nothing, dropping the register's
    // filters AND an open `?open=`/`?tab=` deep link. The register's own effect put its params back
    // a tick later, so the URL briefly described a view nobody was looking at — and the pushed
    // history entry kept that wrong URL for good.
    search = "f.bucket=in%3AT0_T7&sort=dpd&dir=desc&open=12&tab=repayments";
    push.mockClear();
    renderPage();
    await screen.findByRole("table", { name: /Collections worklist/ });

    fireEvent.click(screen.getByRole("button", { name: /31–60 DPD/ }));

    const url = push.mock.calls.at(-1)?.[0] as string;
    // The card reads "31–60 DPD"; the stored enum is T30_T60 — the whole reason `raw` exists.
    expect(url).toContain("bucket=T30_T60");
    expect(url).toContain("f.bucket=in%3AT0_T7");
    expect(url).toContain("sort=dpd");
    expect(url).toContain("open=12");
    expect(url).toContain("tab=repayments");
  });

  it("shows days to due for loans not yet due, measured in IST", async () => {
    renderPage();
    await screen.findByRole("table", { name: "Collections worklist · Upcoming" });
    expect(within(bodyRow(10)).getByText("due in 5 d")).toBeInTheDocument();
    expect(within(bodyRow(11)).getByText("due today")).toBeInTheDocument();
    // Not overdue: no row-level cue on the leading cell.
    expect(bodyRow(10).cells[0].className).not.toContain("inset_3px");
  });

  it("marks an overdue row on its leading cell and keeps the DPD count", async () => {
    search = "bucket=T0_T7";
    renderPage();
    await screen.findByRole("table", { name: "Collections worklist · 1–7 DPD" });
    const tr = bodyRow(12);
    expect(tr.cells[0].className).toContain("shadow-[inset_3px_0_0_0_theme(colors.error.600)]");
    const dpd = within(tr).getByText("3");
    expect(dpd).toHaveClass("text-error-700");
  });

  it("says the bulk selection is scoped to this page", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select loan #10" }));
    expect(screen.getByText("1 selected on this page")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Assign selected" })).toBeInTheDocument();
  });

  it("does not refetch the export enrichment when the menu is reopened", async () => {
    const byIdsAll = vi.spyOn(customersApi, "byIdsAll").mockResolvedValue([]);
    localStorage.setItem("navix-staff-working-role:1", "ADMIN"); // Export is an Admin-role button
    renderPage();
    const exportButton = await screen.findByRole("button", { name: /Export/ });
    await waitFor(() => expect(exportButton).toBeEnabled());

    fireEvent.click(exportButton); // open → fetch
    await waitFor(() => expect(byIdsAll).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(exportButton).toBeEnabled());
    fireEvent.click(exportButton); // close
    fireEvent.click(exportButton); // reopen within the staleTime → served from cache
    await waitFor(() => expect(exportButton).toBeEnabled());
    expect(byIdsAll).toHaveBeenCalledTimes(1);
    // The page on screen (UPCOMING: loans 10 and 11) — not the whole worklist, which is no longer loaded here.
    expect(byIdsAll).toHaveBeenCalledWith([510, 511]);
  });

  it("asks the server for the saved view and renders exactly the page it returns", async () => {
    // A saved view travels as the URL's own params: search, sort, column filters — the register owns
    // them, the server applies them, and the page does not re-filter what comes back.
    search = "bucket=T0_T7&q=asha&sort=principalPaise&dir=asc&f.dpd=num%3A1..&page=1";
    const page = vi.spyOn(collectionsApi, "worklistPage").mockResolvedValue({ rows: [ROWS[2]], page: 1, size: 25, total: 41 });
    renderPage();
    await screen.findByRole("table", { name: "Collections worklist · 1–7 DPD" });

    const call = page.mock.calls[0][0];
    expect(call.filters).toMatchObject({ q: "asha", sort: "principalPaise", dir: "asc", "f.dpd": "num:1.." });
    expect(call.extra).toEqual({ bucket: "T0_T7" });
    expect(call.page).toBe(1);
    // One row on screen, and the count is the SERVER's total across pages, not rows.length.
    expect(screen.getAllByRole("checkbox", { name: /Select loan #/ })).toHaveLength(1);
    // The pagination owns this number and is the only thing that prints it — the filter bar speaks
    // only when a filter has emptied the register.
    // (JSX splits this line into several text nodes, so match on the element's own text.)
    expect(
      screen.getByText((_t, el) => el?.tagName === "SPAN" && /Showing .* of 41/.test(el.textContent ?? "")),
    ).toBeInTheDocument();
  });

  it("keeps the bucket cards on the unfiltered worklist while the register is filtered", async () => {
    // The cards are navigation as well as counts: a filter that empties the register must not zero them.
    search = "bucket=UPCOMING&f.dpd=num%3A99..";
    vi.spyOn(collectionsApi, "worklistPage").mockResolvedValue({ rows: [], page: 1, size: 25, total: 0 });
    renderPage();
    await screen.findByText(/No loans in .* match the column filters/);

    // 3 live loans in total on the worklist (2 upcoming + 1 in 1–7), whatever the register shows.
    expect(screen.getByRole("button", { name: /All loans/ })).toHaveTextContent("3");
    expect(screen.getByRole("button", { name: /1–7 DPD/ })).toHaveTextContent("1");
  });

  it("Clear filters drops the column filters but leaves the search term alone", async () => {
    // The bar's onClear is `setFilters({})`, not `clearAll`, which would also wipe the search box.
    search = "bucket=UPCOMING&q=asha&f.dpd=num%3A1..";
    const page = vi.spyOn(collectionsApi, "worklistPage");
    renderPage();
    await screen.findByRole("table", { name: /Collections worklist/ });

    fireEvent.click(screen.getByRole("button", { name: /Clear filters/ }));

    await waitFor(() => {
      const last = page.mock.calls.at(-1)![0];
      expect(last.filters?.["f.dpd"]).toBeUndefined();
      expect(last.filters?.q).toBe("asha");
    });
    expect(screen.getByPlaceholderText(/Customer ID, name, mobile/)).toHaveValue("asha");
  });

  it("does not move the bucket cards when a column filter is cleared or applied", async () => {
    // The cards are the unfiltered map of the book and the page's navigation: no filter change may
    // touch them, and none may refetch the summary they come from.
    search = "bucket=UPCOMING&f.dpd=num%3A99..";
    const page = vi.spyOn(collectionsApi, "worklistPage");
    renderPage();
    const all = await screen.findByRole("button", { name: /All loans/ });
    await waitFor(() => expect(all).toHaveTextContent("3"));
    const summaryCalls = vi.mocked(collectionsApi.worklistSummary).mock.calls.length;

    fireEvent.click(screen.getByRole("button", { name: /Clear filters/ }));
    await waitFor(() => expect(page.mock.calls.at(-1)![0].filters?.["f.dpd"]).toBeUndefined());

    expect(screen.getByRole("button", { name: /All loans/ })).toHaveTextContent("3");
    expect(screen.getByRole("button", { name: /1–7 DPD/ })).toHaveTextContent("1");
    expect(collectionsApi.worklistSummary).toHaveBeenCalledTimes(summaryCalls);
  });

  it("never loads the whole worklist for the cards", async () => {
    const whole = vi.spyOn(collectionsApi, "worklist");
    renderPage();
    await screen.findByRole("table", { name: /Collections worklist/ });
    expect(whole).not.toHaveBeenCalled();
    expect(collectionsApi.worklistSummary).toHaveBeenCalled();
  });

  it("fetches no column facet on mount", async () => {
    const facet = vi.spyOn(collectionsApi, "worklistFacet");
    renderPage();
    await screen.findByRole("table", { name: /Collections worklist/ });
    expect(facet).not.toHaveBeenCalled();
  });

  it("never leaves a selected loan that a new view no longer shows", async () => {
    // Bulk assign acts on `selectedLoanIds`; after the server returns a narrower page (a filter, a
    // poll, a page change) a loan that dropped out must be unselected, not silently assigned.
    const page = vi.spyOn(collectionsApi, "worklistPage");
    renderPage();
    fireEvent.click(await screen.findByRole("checkbox", { name: "Select loan #10" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Select loan #11" }));
    expect(screen.getByText("2 selected on this page")).toBeInTheDocument();

    page.mockResolvedValue({ rows: [ROWS[1]], page: 1, size: 25, total: 1 });
    fireEvent.click(screen.getByRole("button", { name: /Refresh/ }));

    await waitFor(() => expect(screen.queryByRole("checkbox", { name: "Select loan #10" })).not.toBeInTheDocument());
    // `useQueueSelection` resets when the row count changes, and the page intersects with what is on
    // screen besides — either way the hidden loan can no longer be in a bulk assign.
    expect(screen.queryByText("2 selected on this page")).not.toBeInTheDocument();
  });

  it("select-all ticks only the rows on screen", async () => {
    search = "bucket=UPCOMING";
    renderPage();
    await screen.findByRole("checkbox", { name: "Select loan #10" });
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all loans on this page" }));
    // The register shows loans 10 and 11 (the server's UPCOMING page); loan 12 is not in the view.
    expect(screen.getByText("2 selected on this page")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Select loan #12" })).not.toBeInTheDocument();
  });

  it("exports every matching row, read page by page under the same view", async () => {
    localStorage.setItem("navix-staff-working-role:1", "ADMIN");
    search = "q=asha&f.dpd=num%3A1..";
    vi.spyOn(customersApi, "byIdsAll").mockResolvedValue([]);
    vi.spyOn(customersApi, "collectionHandoverAll").mockResolvedValue([]);
    const page = vi.spyOn(collectionsApi, "worklistPage").mockImplementation(async (p) => {
      // 3 matching loans served 2 at a time, as a server with a small page cap would.
      const all = [ROWS[2], ROWS[0], ROWS[1]];
      const from = (p.page - 1) * 2;
      return { rows: all.slice(from, from + 2), page: p.page, size: 2, total: 3 };
    });
    renderPage();
    const exportButton = await screen.findByRole("button", { name: /Export/ });
    await waitFor(() => expect(exportButton).toBeEnabled());
    fireEvent.click(exportButton);
    fireEvent.click(await screen.findByRole("button", { name: /Download all loans/ }));

    await waitFor(() => expect(exportCsv).toHaveBeenCalled());
    const [, , exported] = vi.mocked(exportCsv).mock.calls.at(-1)!;
    expect((exported as Array<{ loanId: number }>).map((r) => r.loanId).sort()).toEqual([10, 11, 12]);
    // Same search + filter as the table, and it kept asking until it had the server's total.
    const exportCalls = page.mock.calls.filter(([a]) => a.size === 200).map(([a]) => a);
    expect(exportCalls[0].filters).toMatchObject({ q: "asha", "f.dpd": "num:1.." });
    expect(exportCalls.length).toBeGreaterThanOrEqual(1);
  });
});
