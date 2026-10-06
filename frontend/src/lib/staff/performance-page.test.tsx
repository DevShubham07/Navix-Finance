/**
 * Page-level checks for `/staff/performance` (Phase 2): the error branch keeps the page usable, a
 * refusal still reads as "no access", empty rosters and filtered-out rosters say different things,
 * and the sort round-trips through the URL.
 *
 * Lives under `src/lib/staff` beside the helpers it exercises; the page itself is imported by path.
 */
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApplicationApiError, staffApi, type StaffPerformanceRow } from "@/lib/api/applications";
// `vi.mock` below is hoisted above every import, so the page sees the mocked navigation hooks.
import StaffPerformancePage from "@/app/staff/performance/page";

const replace = vi.fn();
let search = "";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/performance",
  useSearchParams: () => new URLSearchParams(search),
}));

function row(overrides: Partial<StaffPerformanceRow> = {}): StaffPerformanceRow {
  return {
    staffId: 1,
    staffName: "Asha",
    role: "CREDIT_EXECUTIVE",
    active: true,
    accepted: 3,
    rejected: 1,
    totalActions: 6,
    activeDays: 2,
    avgTurnaroundMinutes: null,
    pendingNow: 2,
    moneyPaise: 0,
    firstActionAt: null,
    lastActionAt: null,
    verifiedCount: null,
    verifiedPaise: null,
    rejectedPaymentCount: null,
    callsMade: 0,
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffPerformancePage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  search = "";
  replace.mockReset();
  // `useStaffMe` (via ExportMenu) asks the BFF who is signed in; answer "nobody" without a network.
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ session: null })));
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("StaffPerformancePage", () => {
  it("keeps the header and period picker on a transient error, with a retry in place of the table", async () => {
    vi.spyOn(staffApi, "performance").mockRejectedValue(new ApplicationApiError("Bad gateway", "HTTP_502", 502));
    renderPage();

    expect(await screen.findByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Staff performance" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "This month" })).toBeInTheDocument();
    // No data: every tile is an em dash, never a measured-looking zero.
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(5);
  });

  it("still shows the no-access notice when the server refuses the caller", async () => {
    vi.spyOn(staffApi, "performance").mockRejectedValue(
      new ApplicationApiError("DSAs cannot view decision history", "FORBIDDEN_ROLE", 422),
    );
    renderPage();

    expect(await screen.findByText(/DSAs cannot view decision history/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "This month" })).not.toBeInTheDocument();
  });

  it("says the scope is empty when the roster is", async () => {
    vi.spyOn(staffApi, "performance").mockResolvedValue({ rows: [], daily: [], callTrackingSince: "2026-07-01" });
    renderPage();

    expect(await screen.findByText("No staff in your scope")).toBeInTheDocument();
  });

  it("keeps the last window's figures, marked busy, while the next period loads", async () => {
    const perf = vi.spyOn(staffApi, "performance").mockResolvedValue({
      rows: [row(), row({ staffId: 2, staffName: "Bilal", accepted: 9, totalActions: 9 })],
      daily: [{ date: "2026-10-01", actions: 3 }],
      callTrackingSince: "2026-07-01",
    });
    renderPage();
    await screen.findAllByText("Bilal");
    expect(screen.getByText("12")).toBeInTheDocument(); // Approved: 3 + 9

    // The next window never arrives in this test: the previous figures must stand in, flagged busy.
    perf.mockImplementation(() => new Promise(() => {}));
    fireEvent.click(screen.getByRole("button", { name: "Today" }));
    await waitFor(() => expect(perf).toHaveBeenCalledTimes(2));
    const approved = screen.getByText("12");
    expect(approved.closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Actions per day").closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
    expect(screen.getAllByText("Bilal").length).toBeGreaterThan(0);
  });

  it("reads the sort from the URL and writes a change back with replace", async () => {
    search = "sort=staffName&dir=asc";
    vi.spyOn(staffApi, "performance").mockResolvedValue({
      rows: [row(), row({ staffId: 2, staffName: "Bilal", accepted: 9 })],
      daily: [],
      callTrackingSince: "2026-07-01",
    });
    renderPage();

    await screen.findAllByText("Bilal");
    const staffHeader = screen.getAllByRole("columnheader").find((th) => th.textContent?.includes("Staff"));
    expect(staffHeader).toHaveAttribute("aria-sort", "ascending");
    expect(replace).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Sort by Approved" }));
    await waitFor(() =>
      expect(replace).toHaveBeenLastCalledWith("/staff/performance?sort=accepted", { scroll: false }),
    );
  });
});
