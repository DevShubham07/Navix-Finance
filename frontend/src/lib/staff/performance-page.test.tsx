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
import { perfRow } from "@/lib/staff/performance-fixtures";
// `vi.mock` below is hoisted above every import, so the page sees the mocked navigation hooks.
import StaffPerformancePage from "@/app/staff/performance/page";

const replace = vi.fn();
let search = "";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/performance",
  useSearchParams: () => new URLSearchParams(search),
}));

function row(overrides: Parameters<typeof perfRow>[0] = {}): StaffPerformanceRow {
  return perfRow({
    staffName: "Asha",
    actionCounts: { SANCTION: 3, REJECT_LEAD: 1, MARK_PENDING: 2 },
    totalActions: 6,
    activeDays: 2,
    pendingNow: 2,
    ...overrides,
  });
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
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(4);
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
      rows: [
        row({ daily: [{ date: "2026-10-01", actions: 3 }] }),
        row({ staffId: 2, staffName: "Bilal", actionCounts: { SANCTION: 9 }, totalActions: 9 }),
      ],
      daily: [{ date: "2026-10-01", actions: 3 }],
      callTrackingSince: "2026-07-01",
    });
    renderPage();
    await screen.findAllByText("Bilal");
    expect(screen.getByText("12")).toBeInTheDocument(); // Sanctioned: 3 + 9

    // The next window never arrives in this test: the previous figures must stand in, flagged busy.
    perf.mockImplementation(() => new Promise(() => {}));
    fireEvent.click(screen.getByRole("button", { name: "Today" }));
    await waitFor(() => expect(perf).toHaveBeenCalledTimes(2));
    const approved = screen.getByText("12");
    expect(approved.closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText(/Activity per day/).closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
    expect(screen.getAllByText("Bilal").length).toBeGreaterThan(0);
  });

  it("reads the sort from the URL and writes a change back with replace", async () => {
    search = "sort=staffName&dir=asc";
    vi.spyOn(staffApi, "performance").mockResolvedValue({
      rows: [row(), row({ staffId: 2, staffName: "Bilal", actionCounts: { SANCTION: 9 } })],
      daily: [],
      callTrackingSince: "2026-07-01",
    });
    renderPage();

    await screen.findAllByText("Bilal");
    const staffHeader = screen.getAllByRole("columnheader").find((th) => th.textContent?.includes("Staff"));
    expect(staffHeader).toHaveAttribute("aria-sort", "ascending");
    expect(replace).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Sort by Parked" }));
    await waitFor(() =>
      expect(replace).toHaveBeenLastCalledWith("/staff/performance?sort=parked", { scroll: false }),
    );
  });

  it("switches entity to show that role's own parameters and only its staff", async () => {
    vi.spyOn(staffApi, "performance").mockResolvedValue({
      rows: [
        row(),
        row({
          staffId: 2,
          staffName: "Bilal",
          role: "COLLECTION_EXECUTIVE",
          actionCounts: {},
          collections: { interactions: 10, noAnswer: 4, promisesToPay: 3, casesNow: 7 },
        }),
      ],
      daily: [],
      callTrackingSince: "2026-07-01",
    });
    renderPage();

    // Defaults to the first entity on offer: credit, with Asha alone and credit columns.
    await screen.findAllByText("Asha");
    expect(screen.getByRole("tab", { name: /Credit Executive/ })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByText("Bilal")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: /Collection Executive/ }));
    await screen.findAllByText("Bilal");
    expect(screen.queryByText("Asha")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sort by Promises to pay" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sort by Sanctioned" })).not.toBeInTheDocument();
    expect(screen.getAllByText("60%").length).toBeGreaterThan(0); // contact rate: 6 of 10 reached
    expect(replace).toHaveBeenCalledWith("/staff/performance?entity=COLLECTION_EXECUTIVE", { scroll: false });
  });

  it("opens a pop-up explaining the person's figures for their role", async () => {
    search = "entity=COLLECTION_EXECUTIVE";
    vi.spyOn(staffApi, "performance").mockResolvedValue({
      rows: [
        row({
          staffId: 2,
          staffName: "Bilal",
          role: "COLLECTION_EXECUTIVE",
          actionCounts: {},
          collections: { interactions: 10, noAnswer: 4, promisesToPay: 3 },
        }),
      ],
      daily: [],
      callTrackingSince: "2026-07-01",
    });
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "View Bilal's performance" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("As Collection Executive");
    expect(dialog).toHaveTextContent("Call outcomes (10)");
    expect(dialog).toHaveTextContent("Promise to pay");
    expect(dialog).toHaveTextContent(/every outcome except “No answer”/);
  });
});
