/**
 * `/staff/collections/settlements` (Phase 2): status chips with counts, PROPOSED rows first under
 * All, the pre-flight SoD notice for the staffer's own proposals, and the action-error banner
 * clearing when a new decision starts.
 *
 * Lives under `src/lib/collections` beside the helpers it exercises; the page is imported by path.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApplicationApiError, collectionsApi, type SettlementView } from "@/lib/api/applications";
import CollectionsSettlementsPage from "@/app/staff/collections/settlements/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/collections/settlements",
  useSearchParams: () => new URLSearchParams(""),
}));

const ME = { id: "7", name: "Head Seven", role: "COLLECTION_HEAD" };

function settlement(overrides: Partial<SettlementView>): SettlementView {
  return {
    id: "00000000-0000-0000-0000-000000000000",
    collectionCaseId: "11111111-1111-1111-1111-111111111111",
    settlementAmountPaise: 500_000,
    proposedBy: 9,
    proposedByName: "Officer Nine",
    approvedBy: null,
    approvedByName: null,
    rejectedBy: null,
    rejectedByName: null,
    status: "PROPOSED",
    createdAt: "2026-10-01T10:00:00Z",
    approvedAt: null,
    rejectedAt: null,
    ...overrides,
  };
}

const APPROVED = settlement({
  id: "aaaaaaaa-0000-0000-0000-000000000001",
  status: "APPROVED",
  approvedBy: 3,
  approvedByName: "Head Three",
  approvedAt: "2026-10-02T10:00:00Z",
});
const MINE = settlement({ id: "bbbbbbbb-0000-0000-0000-000000000002", proposedBy: 7, proposedByName: "Head Seven" });
const THEIRS = settlement({ id: "cccccccc-0000-0000-0000-000000000003", proposedBy: 9 });

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CollectionsSettlementsPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session: ME })));
  vi.spyOn(collectionsApi, "listSettlements").mockResolvedValue([APPROVED, MINE, THEIRS]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

function bodyRows() {
  const table = screen.getByRole("table", { name: "Collection settlements" });
  return within(table).getAllByRole("row").slice(1);
}

describe("CollectionsSettlementsPage", () => {
  it("counts each status on its chip and lists PROPOSED rows first under All", async () => {
    renderPage();
    expect(await screen.findByRole("button", { name: "All (3)" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Proposed (2)" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approved (1)" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rejected (0)" })).toBeInTheDocument();

    const rows = bodyRows();
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("bbbbbbbb");
    expect(rows[1]).toHaveTextContent("cccccccc");
    expect(rows[2]).toHaveTextContent("aaaaaaaa");
  });

  it("narrows to one status, and says so when that status is empty", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Approved (1)" }));
    expect(bodyRows()).toHaveLength(1);
    expect(bodyRows()[0]).toHaveTextContent("aaaaaaaa");

    fireEvent.click(screen.getByRole("button", { name: "Rejected (0)" }));
    expect(screen.getByText("No rejected settlements.")).toBeInTheDocument();
  });

  it("replaces Approve/Reject on the staffer's own proposal with the SoD notice", async () => {
    renderPage();
    expect(await screen.findByText("You proposed this — another Collection Head must decide")).toBeInTheDocument();
    // Only the other officer's proposal offers the decision.
    await waitFor(() => expect(screen.getAllByRole("button", { name: "Approve" })).toHaveLength(1));
    const [mine, theirs] = bodyRows();
    expect(within(mine).queryByRole("button", { name: "Approve" })).toBeNull();
    expect(within(theirs).getByRole("button", { name: "Approve" })).toBeInTheDocument();
  });

  it("clears an old action error when a new decision starts", async () => {
    vi.spyOn(collectionsApi, "approveSettlement").mockRejectedValue(
      new ApplicationApiError("The approver must differ from the proposer", "SOD_VIOLATION", 422),
    );
    vi.spyOn(collectionsApi, "rejectSettlement").mockResolvedValue({ ...THEIRS, status: "REJECTED" });
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    expect(await screen.findByText(/must differ from the proposer/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Reject" }));
    await waitFor(() => expect(screen.queryByText(/must differ from the proposer/)).toBeNull());
  });

  it("offers the full ids behind the truncated ones", async () => {
    renderPage();
    const [first] = await screen.findAllByRole("button", { name: "Full settlement and case ids" });
    fireEvent.click(first);
    const tip = await screen.findByRole("tooltip");
    expect(tip).toHaveTextContent(MINE.id);
    expect(tip).toHaveTextContent(MINE.collectionCaseId);
  });
});
