/**
 * `<CollectionsHeadDesk/>` (the two panels above the DPD register on `/staff/collections`): renders
 * for a Collection Head and stays hidden for an executive; the assignment panel asks the worklist
 * for unassigned rows only (the register's own `__NULL__` officer filter) and lists them most
 * overdue first; the tracker shows one row per officer plus a totals row, and a count narrows the
 * register to that officer.
 *
 * Lives under `src/lib/collections` beside the worklist page test; the component is imported by path.
 */
import { render, screen, fireEvent, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { collectionsApi, dashboardApi, type DashTeamDay, type LoanSummary, type WorklistRow } from "@/lib/api/applications";
import { CollectionsHeadDesk, headDeskPrefsKey, istDayPlus, sortUnassigned } from "@/components/staff/collections/head-desk";

const HEAD = { id: "9", name: "Meera", role: "COLLECTION_HEAD" };
const EXEC = { id: "7", name: "Rahul", role: "COLLECTION_EXECUTIVE" };

function loan(loanId: number, dueDate: string, outstanding = 1_270_000): LoanSummary {
  return {
    loanId,
    customerId: 500 + loanId,
    applicationId: null,
    status: "ACTIVE",
    principalPaise: 1_000_000,
    netDisbursedPaise: 882_000,
    totalRepayablePaise: 1_270_000,
    outstandingPaise: outstanding,
    disbursedOn: "2026-09-10",
    dueDate,
    borrowerName: `Borrower ${loanId}`,
    pan: null,
    mobile: `98765${String(loanId).padStart(5, "0")}`,
    employer: null,
    employmentStatus: null,
    monthlySalaryPaise: null,
    salaryBank: null,
    preDue: false,
  };
}

function row(loanId: number, dpd: number, dueDate: string, officerId: number | null): WorklistRow {
  return {
    loanId,
    dpd,
    bucket: dpd > 0 ? "T0_T7" : "UPCOMING",
    preDue: dpd === 0,
    caseId: officerId == null ? null : `case-${loanId}`,
    assignedOfficerId: officerId,
    assignedOfficerName: officerId == null ? null : "Rahul",
    caseOpenedAt: null,
    creditDecidedByName: null,
    disbursedByName: null,
    loan: loan(loanId, dueDate),
  };
}

const ALL_ROWS: WorklistRow[] = [
  row(10, 0, "2026-10-20", null), // pre-due, due in 3 days
  row(11, 5, "2026-10-12", null), // overdue 5 — should lead
  row(12, 2, "2026-10-15", 7), // assigned — must not appear
  row(13, 1, "2026-10-16", null),
];

const TEAM: DashTeamDay = {
  date: "2026-10-17",
  rows: [
    { staffId: 8, name: "Asha", role: "COLLECTION_EXECUTIVE", calls: 0, promisesDue: 0, promisesDuePaise: 0, partPayments: 2, partPaymentsPaise: 50_000, collectedPaise: 50_000, closed: 0, dueCount: 4 },
    { staffId: 7, name: "Rahul", role: "COLLECTION_EXECUTIVE", calls: 12, promisesDue: 3, promisesDuePaise: 300_000, partPayments: 0, partPaymentsPaise: 0, collectedPaise: 125_000, closed: 1, dueCount: 0 },
  ],
  totals: { staffId: null, name: "Total", role: null, calls: 12, promisesDue: 3, promisesDuePaise: 300_000, partPayments: 2, partPaymentsPaise: 50_000, collectedPaise: 175_000, closed: 1, dueCount: 4 },
};

function renderDesk(onFilterOfficer = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <CollectionsHeadDesk onFilterOfficer={onFilterOfficer} />
    </QueryClientProvider>,
  );
  return { onFilterOfficer };
}

function session(me: typeof HEAD) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session: me })));
}

beforeEach(() => {
  // 2026-10-17 10:00 IST.
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-17T04:30:00Z"));
  // The fake server applies the one filter the panel sends, the way the real one does.
  vi.spyOn(collectionsApi, "worklistPage").mockImplementation(async (p) => {
    const unassignedOnly = p.filters?.["f.officerName"] === "in:__NULL__";
    const rows = ALL_ROWS.filter((r) => !unassignedOnly || r.assignedOfficerId == null);
    return { rows, page: p.page, size: p.size, total: rows.length };
  });
  vi.spyOn(collectionsApi, "listOfficers").mockResolvedValue([
    { id: 7, name: "Rahul", role: "COLLECTION_EXECUTIVE", active: true },
  ]);
  vi.spyOn(dashboardApi, "collectionsTeam").mockResolvedValue(TEAM);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("<CollectionsHeadDesk/>", () => {
  it("renders nothing for a collection executive", async () => {
    session(EXEC);
    renderDesk();
    // Let the session resolve, then confirm neither panel mounted and nothing was fetched for them.
    await vi.waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
    await Promise.resolve();
    expect(screen.queryByTestId("collections-head-desk")).not.toBeInTheDocument();
    expect(collectionsApi.worklistPage).not.toHaveBeenCalled();
    expect(dashboardApi.collectionsTeam).not.toHaveBeenCalled();
  });

  it("lists only the unassigned loans, most overdue first, under the register's own filter", async () => {
    session(HEAD);
    renderDesk();
    const table = await screen.findByRole("table", { name: "Loans needing a collections executive" });

    expect(collectionsApi.worklistPage).toHaveBeenCalledWith(
      expect.objectContaining({ filters: expect.objectContaining({ "f.officerName": "in:__NULL__" }) }),
    );
    expect(screen.getByRole("heading", { name: /Needs assignment · 3/ })).toBeInTheDocument();

    const bodyRows = within(table).getAllByRole("row").slice(1);
    expect(bodyRows.map((r) => r.getAttribute("data-testid"))).toEqual(["unassigned-11", "unassigned-13", "unassigned-10"]);
    expect(screen.queryByTestId("unassigned-12")).not.toBeInTheDocument(); // Rahul's loan
    expect(within(bodyRows[2]).getByText("due in 3 d")).toBeInTheDocument();
    // Each row carries the inline picker, with "Assign to me" for the Head.
    expect(within(bodyRows[0]).getByRole("combobox", { name: "Collections executive for loan #11" })).toBeInTheDocument();
    expect(within(bodyRows[0]).getByRole("option", { name: "Assign to me" })).toBeInTheDocument();
  });

  it("shows the team's day with a totals row and narrows the register from a count", async () => {
    session(HEAD);
    const { onFilterOfficer } = renderDesk();
    await screen.findByRole("table", { name: /Collections team/ });

    expect(dashboardApi.collectionsTeam).toHaveBeenCalledWith("COLLECTION_HEAD", "2026-10-17");
    const totals = screen.getByTestId("team-totals");
    expect(totals).toHaveTextContent("Total");
    expect(totals).toHaveTextContent("12"); // calls
    expect(totals).toHaveTextContent("₹1,750"); // collected
    expect(totals).toHaveTextContent("3 · ₹3,000"); // promises due

    fireEvent.click(screen.getByRole("button", { name: "Calls logged by Rahul" }));
    expect(onFilterOfficer).toHaveBeenCalledWith(7);

    // Tomorrow asks the server for the next IST day and swaps to the due-tomorrow columns.
    fireEvent.click(screen.getByRole("tab", { name: "Tomorrow" }));
    await vi.waitFor(() => expect(dashboardApi.collectionsTeam).toHaveBeenCalledWith("COLLECTION_HEAD", "2026-10-18"));
    expect(await screen.findByRole("columnheader", { name: "Falling due" })).toBeInTheDocument();
  });

  it("remembers a collapsed panel per staffer", async () => {
    session(HEAD);
    localStorage.setItem(headDeskPrefsKey("9"), JSON.stringify({ assign: true, team: false }));
    renderDesk();
    await screen.findByRole("table", { name: /Collections team/ });
    const toggle = screen.getByRole("button", { name: /^Needs assignment/ });
    await vi.waitFor(() => expect(toggle).toHaveAttribute("aria-expanded", "false"));
    expect(screen.queryByRole("table", { name: "Loans needing a collections executive" })).not.toBeInTheDocument();

    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(JSON.parse(localStorage.getItem(headDeskPrefsKey("9")) ?? "{}")).toEqual({ assign: false, team: false });
  });
});

describe("head-desk helpers", () => {
  it("orders most overdue first, then due soonest", () => {
    expect(sortUnassigned(ALL_ROWS).map((r) => r.loanId)).toEqual([11, 12, 13, 10]);
  });

  it("measures the IST calendar day", () => {
    // 23:30 UTC on the 17th is already the 18th in IST.
    expect(istDayPlus(new Date("2026-10-17T23:30:00Z"), 0)).toBe("2026-10-18");
    expect(istDayPlus(new Date("2026-10-17T04:30:00Z"), 1)).toBe("2026-10-18");
  });
});
