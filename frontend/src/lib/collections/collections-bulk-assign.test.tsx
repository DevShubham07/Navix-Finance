/**
 * `BulkAssignOfficerDialog` (Phase 2): loans run with bounded concurrency, each loan still GETs its
 * case before opening one, progress is counted while the run is in flight, and the result is a
 * per-loan summary.
 *
 * Lives under `src/lib/collections` beside `bulk-assign-outcomes.ts`; the dialog is imported from
 * `components/staff/collections-assign.tsx`.
 */
import { render, screen, fireEvent, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApplicationApiError, collectionsApi, type CaseDetailView } from "@/lib/api/applications";
import { BulkAssignOfficerDialog } from "@/components/staff/collections-assign";

const ME = { id: "1", name: "Head", role: "COLLECTION_HEAD" };

function caseFor(loanId: number): CaseDetailView {
  return {
    id: `case-${loanId}`,
    loanId,
    assignedOfficerId: null,
    assignedOfficerName: null,
    createdAt: "2026-10-01T10:00:00Z",
    dpd: 3,
    bucket: "T0_T7",
    loan: null,
  };
}

function renderDialog(loanIds: number[], onDone = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <BulkAssignOfficerDialog loanIds={loanIds} open onClose={vi.fn()} onDone={onDone} />
    </QueryClientProvider>,
  );
  return { onDone };
}

beforeEach(() => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session: ME })));
  vi.spyOn(collectionsApi, "listOfficers").mockResolvedValue([
    { id: 42, name: "Ravi", role: "COLLECTION_EXECUTIVE", active: true },
  ]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("<BulkAssignOfficerDialog/>", () => {
  it("runs at most four loans at once, keeps the pre-GET, counts progress and summarises per loan", async () => {
    const loanIds = [1, 2, 3, 4, 5, 6];
    let inFlight = 0;
    let maxInFlight = 0;
    let release!: () => void;
    const gate = new Promise<void>((r) => {
      release = r;
    });

    // Odd loans already have a case; even ones need one opened.
    const caseByLoan = vi.spyOn(collectionsApi, "caseByLoan").mockImplementation(async (loanId) => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      return loanId % 2 === 1 ? caseFor(loanId) : null;
    });
    const openCase = vi.spyOn(collectionsApi, "openCase").mockImplementation(async (loanId) => caseFor(loanId));
    vi.spyOn(collectionsApi, "assignOfficer").mockImplementation(async (caseId) => {
      await gate;
      inFlight -= 1;
      if (caseId === "case-4") throw new ApplicationApiError("Loan is closed", "LOAN_CLOSED", 422);
      return caseFor(Number(caseId.slice(5)));
    });

    const { onDone } = renderDialog(loanIds);
    fireEvent.change(await screen.findByLabelText("Collections executive"), { target: { value: "42" } });
    fireEvent.click(screen.getByRole("button", { name: "Assign" }));

    expect(await screen.findByRole("status")).toHaveTextContent("0 / 6 assigned");
    expect(maxInFlight).toBe(4);

    release();

    const list = await screen.findByRole("list", { name: "Result per loan" });
    const items = within(list).getAllByRole("listitem");
    expect(items.map((li) => li.textContent)).toEqual([
      "Loan #1Assigned",
      "Loan #2Assigned",
      "Loan #3Assigned",
      expect.stringMatching(/^Loan #4.*Loan is closed/),
      "Loan #5Assigned",
      "Loan #6Assigned",
    ]);
    expect(screen.getByText(/5 assigned/)).toHaveTextContent("5 assigned, 1 failed.");
    expect(maxInFlight).toBeLessThanOrEqual(4);

    // Every loan was looked up first; only the ones without a case had one opened.
    expect(caseByLoan).toHaveBeenCalledTimes(6);
    expect(openCase.mock.calls.map(([id]) => id).sort()).toEqual([2, 4, 6]);
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});
