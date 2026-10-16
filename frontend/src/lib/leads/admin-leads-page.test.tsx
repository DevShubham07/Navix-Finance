/**
 * Page-level checks for `/staff/admin/leads`: the register's filters run on the server, the page-level
 * selects (window, telecaller, outcome) ride beside the column filters, and no control is duplicated.
 */
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { adminApi, leadsApi, type LeadPage, type LeadView } from "@/lib/api/applications";
// `vi.mock` calls below are hoisted above every import, so the page sees the mocked modules.
import AdminLeadsPage from "@/app/staff/admin/leads/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/admin/leads",
  useSearchParams: () => new URLSearchParams(),
}));

// The tracker's own rendering is not under test here; only what the page says it follows.
vi.mock("@/components/staff/leads-tracker", () => ({ LeadsTracker: () => <div data-testid="tracker" /> }));

function lead(overrides: Partial<LeadView> = {}): LeadView {
  return {
    id: 11,
    name: "Asha Verma",
    mobile: "9876543210",
    pincode: "411001",
    email: null,
    city: "Pune",
    employer: null,
    monthlySalaryPaise: null,
    loanAmountInterestedPaise: null,
    source: "WALK_IN",
    sourceDetail: null,
    callStatus: "NOT_CALLED",
    qualityRating: null,
    notes: null,
    remarks: null,
    createdByStaffId: 5,
    createdByStaffName: "Tara",
    createdAt: "2026-10-01T05:30:00Z",
    updatedAt: null,
    leadOutcome: "NEW",
    dsaNote: null,
    ...overrides,
  } as LeadView;
}

const pageOf = (rows: LeadView[]): LeadPage => ({ rows, page: 1, size: 25, total: rows.length });

function renderPage(role = "ADMIN") {
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ session: { id: 1, name: "Root", role } })),
  );
  vi.spyOn(adminApi, "listStaff").mockResolvedValue([]);
  vi.spyOn(leadsApi, "stats").mockResolvedValue({
    total: 0, byCallStatus: [], bySource: [], byRating: [], unrated: 0, byDay: [], byStaff: [], avgRating: null,
  } as never);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AdminLeadsPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe("admin leads register", () => {
  it("pages through the server with the window as a view param, and asks no facet on mount", async () => {
    const page = vi.spyOn(leadsApi, "page").mockResolvedValue(pageOf([lead()]));
    const facet = vi.spyOn(leadsApi, "facet");
    renderPage();
    expect(await screen.findByText("Asha Verma")).toBeInTheDocument();

    const { extra } = page.mock.calls[0][0];
    expect(extra).toEqual(expect.objectContaining({ from: expect.any(String), to: expect.any(String) }));
    expect(extra).not.toHaveProperty("callStatus");
    expect(extra).not.toHaveProperty("source");
    expect(facet).not.toHaveBeenCalled();
  });

  it("keeps ONE control per field: Status and Source are header filters now, not selects", async () => {
    vi.spyOn(leadsApi, "page").mockResolvedValue(pageOf([lead()]));
    renderPage();
    await screen.findByText("Asha Verma");

    expect(screen.queryByLabelText("Status")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Source")).not.toBeInTheDocument();
    // Outcome (derived CONFIRMED the column cannot filter on) and Telecaller (drives the tracker) stay.
    expect(screen.getByLabelText("Outcome")).toBeInTheDocument();
    expect(screen.getByLabelText("Telecaller")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Filter Status" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Filter Source" })).toBeInTheDocument();
    // The source cell reads like the facet's label.
    expect(screen.getByText("WALK IN")).toBeInTheDocument();
  });

  it("sends a Status checklist choice as the raw value, with the page-level params on the facet too", async () => {
    const page = vi.spyOn(leadsApi, "page").mockResolvedValue(pageOf([lead()]));
    const facet = vi
      .spyOn(leadsApi, "facet")
      .mockResolvedValue({ values: ["NOT_CALLED", "NO_ANSWER"], truncated: false });
    renderPage();
    await screen.findByText("Asha Verma");

    fireEvent.click(screen.getByRole("button", { name: "Filter Status" }));
    await waitFor(() =>
      expect(facet).toHaveBeenCalledWith("callStatus", expect.objectContaining({ from: expect.any(String) })),
    );
    // Everything starts ticked once the facet lands (Excel semantics): clear, then tick the one
    // wanted. Clicking it directly would UNtick it.
    fireEvent.click(await screen.findByLabelText("(Select All)"));
    fireEvent.click(screen.getByLabelText("NO ANSWER"));
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    await waitFor(() =>
      expect(page).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 1, extra: expect.objectContaining({ "f.callStatus": "in:NO_ANSWER" }) }),
      ),
    );
  });

  it("sends the Outcome select as a view param and returns to page 1", async () => {
    const page = vi.spyOn(leadsApi, "page").mockResolvedValue(pageOf([lead()]));
    renderPage();
    await screen.findByText("Asha Verma");

    fireEvent.change(screen.getByLabelText("Outcome"), { target: { value: "REJECTED" } });
    await waitFor(() =>
      expect(page).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 1, extra: expect.objectContaining({ leadOutcome: "REJECTED" }) }),
      ),
    );
  });

  it("states what the tracker graphs follow", async () => {
    vi.spyOn(leadsApi, "page").mockResolvedValue(pageOf([lead()]));
    renderPage();
    await screen.findByText("Asha Verma");
    expect(
      screen.getByText("Follows the date range and telecaller only — not the search, outcome or column filters."),
    ).toBeInTheDocument();
  });
});
