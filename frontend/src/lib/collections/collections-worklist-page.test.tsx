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

let search = "";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
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
  localStorage.setItem("navix-staff-working-role:1", "COLLECTION_HEAD"); // ADMIN defaults to CREDIT_HEAD
  // 2026-10-06 00:30 IST — still the 5th in UTC. Only `Date` is faked; timers and promises are real.
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-05T19:00:00Z"));
  search = "bucket=UPCOMING";
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session: ADMIN })));
  vi.spyOn(collectionsApi, "worklist").mockResolvedValue(ROWS);
  vi.spyOn(collectionsApi, "listOfficers").mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("CollectionsBucketPage", () => {
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
    expect(byIdsAll).toHaveBeenCalledWith([510, 511, 512]);
  });
});
