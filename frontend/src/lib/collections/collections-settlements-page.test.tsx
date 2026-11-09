/**
 * `/staff/collections/settlements`: status chips with counts, the pre-flight SoD notice for the
 * staffer's own proposals, and the action-error banner clearing when a new decision starts.
 *
 * Row ORDER is now the register's own sort (newest proposal first) rather than the old
 * "PROPOSED rows floated to the top of All" partition: a register with a sortable Settlement
 * column cannot honour both, and the Pending chip — with its count — is the route to that desk.
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
    loanId: 42,
    customerId: 7001,
    borrowerName: "Ravi Kumar",
    mobile: "9876543210",
    loanStatus: "OVERDUE",
    principalPaise: 1_000_000,
    outstandingPaise: 1_300_000,
    dueDate: "2026-09-30",
    dpd: 12,
    bucket: "T8_T30",
    pan: "ABCDE1234F",
    email: "ravi@example.com",
    employer: "Acme Corp",
    employmentStatus: "SALARIED",
    monthlySalaryPaise: 4_000_000,
    salaryBank: "HDFC",
    accountNumber: "1234567890",
    ifsc: "HDFC0000001",
    bureauScore: 742,
    assignedOfficerName: "Officer Nine",
    disbursedOn: "2026-09-03",
    netDisbursedPaise: 882_000,
    // ₹10,000 principal + ₹2,700 interest + ₹2,000 penalty = ₹14,700 owed; ₹1,700 paid → ₹13,000 left.
    interestPaise: 270_000,
    interestDays: 27,
    penaltyPaise: 200_000,
    penaltyDays: 10,
    paidPaise: 170_000,
    grossDuePaise: 1_470_000,
    ...overrides,
  };
}

const APPROVED = settlement({
  id: "aaaaaaaa-0000-0000-0000-000000000001",
  borrowerName: "Anil Approved",
  status: "APPROVED",
  approvedBy: 3,
  approvedByName: "Head Three",
  approvedAt: "2026-10-02T10:00:00Z",
});
const MINE = settlement({ id: "bbbbbbbb-0000-0000-0000-000000000002", borrowerName: "Meena Mine", proposedBy: 7, proposedByName: "Head Seven" });
const THEIRS = settlement({ id: "cccccccc-0000-0000-0000-000000000003", borrowerName: "Ravi Kumar", proposedBy: 9 });

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
  it("counts each status on its chip and lists rows newest proposal first", async () => {
    renderPage();
    expect(await screen.findByRole("button", { name: "All (3)" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Proposed (2)" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approved (1)" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rejected (0)" })).toBeInTheDocument();

    const rows = bodyRows();
    expect(rows).toHaveLength(3);
    // All three carry the same `createdAt`, so the sort is a no-op tie and server order stands.
    expect(rows[0]).toHaveTextContent("Anil Approved");
    expect(rows[1]).toHaveTextContent("Meena Mine");
    expect(rows[2]).toHaveTextContent("Ravi Kumar");
  });

  it("narrows to one status, and says so when that status is empty", async () => {
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Approved (1)" }));
    expect(bodyRows()).toHaveLength(1);
    expect(bodyRows()[0]).toHaveTextContent("Anil Approved");

    fireEvent.click(screen.getByRole("button", { name: "Rejected (0)" }));
    expect(screen.getByText("No rejected settlements.")).toBeInTheDocument();
  });

  it("replaces Approve/Reject on the staffer's own proposal with the SoD notice", async () => {
    renderPage();
    expect(await screen.findByText("You proposed this — another Collection Head must decide")).toBeInTheDocument();
    // Only the other officer's proposal offers the decision.
    await waitFor(() => expect(screen.getAllByRole("button", { name: "Approve" })).toHaveLength(1));
    const [, mine, theirs] = bodyRows();
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
    fireEvent.click(await screen.findByRole("button", { name: "Approve settlement" }));
    expect(await screen.findByText(/must differ from the proposer/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Reject" }));
    fireEvent.click(await screen.findByRole("button", { name: "Reject settlement" }));
    await waitFor(() => expect(screen.queryByText(/must differ from the proposer/)).toBeNull());
  });

  it("shows the customer and loan behind each settlement, linked", async () => {
    renderPage();
    await screen.findByRole("button", { name: "All (3)" });
    const ravi = bodyRows()[2];
    expect(within(ravi).getByRole("link", { name: "Ravi Kumar" })).toHaveAttribute("href", "/staff/customers/7001");
    expect(within(ravi).getByRole("link", { name: "Loan #42" })).toHaveAttribute("href", "/staff/collections/42");
    expect(ravi).toHaveTextContent("9876543210");
    expect(ravi).toHaveTextContent("ABCDE1234F");
    expect(ravi).toHaveTextContent("1234567890");
    expect(ravi).toHaveTextContent("ravi@example.com");
    // ₹5,000 against ₹14,700 owed: 34% recovered, ₹9,700 lost.
    expect(ravi).toHaveTextContent("34% of dues");
    expect(ravi).toHaveTextContent("₹9,700");
  });

  it("totals due, penalty, net paid and loss per status in the summary table", async () => {
    renderPage();
    const summary = await screen.findByRole("table", { name: "Settlement totals" });
    const [, pendingRow, approvedRow, rejectedRow, totalRow] = within(summary).getAllByRole("row");
    // Two pending (MINE, THEIRS): 2 × ₹14,700 due, 2 × ₹2,000 penalty, 2 × ₹1,700 paid, 2 × ₹9,700 loss.
    expect(pendingRow).toHaveTextContent("Pending2₹29,400₹4,000₹3,400₹10,000₹19,400");
    expect(approvedRow).toHaveTextContent("Approved1₹14,700");
    expect(rejectedRow).toHaveTextContent("Rejected0");
    expect(totalRow).toHaveTextContent("Total3₹44,100₹6,000₹5,100₹15,000₹29,100");
  });

  it("narrows the register and the totals to the chosen period", async () => {
    vi.spyOn(collectionsApi, "listSettlements").mockResolvedValue([
      settlement({ id: "eeeeeeee-0000-0000-0000-000000000005", borrowerName: "Old Proposal", createdAt: "2020-01-15T10:00:00Z" }),
      settlement({ id: "ffffffff-0000-0000-0000-000000000006", borrowerName: "Fresh Proposal", createdAt: new Date().toISOString() }),
    ]);
    renderPage();
    expect(await screen.findByRole("button", { name: "All (2)" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Today" }));
    expect(screen.getByRole("button", { name: "All (1)" })).toBeInTheDocument();
    expect(bodyRows()).toHaveLength(1);
    expect(bodyRows()[0]).toHaveTextContent("Fresh Proposal");
  });

  it("asks for confirmation, restating the waiver, before approving", async () => {
    const approve = vi.spyOn(collectionsApi, "approveSettlement").mockResolvedValue({ ...THEIRS, status: "APPROVED" });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "Approve" }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("Ravi Kumar");
    expect(dialog).toHaveTextContent("Loss (written off)");
    expect(approve).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(approve).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    fireEvent.click(await screen.findByRole("button", { name: "Approve settlement" }));
    await waitFor(() => expect(approve).toHaveBeenCalledWith(THEIRS.id));
  });

  it("searches by customer name or mobile", async () => {
    vi.spyOn(collectionsApi, "listSettlements").mockResolvedValue([
      APPROVED,
      settlement({ id: "dddddddd-0000-0000-0000-000000000004", borrowerName: "Asha Rao", mobile: "9000000001" }),
    ]);
    renderPage();
    fireEvent.change(await screen.findByRole("searchbox", { name: "Search settlements" }), { target: { value: "asha" } });
    expect(bodyRows()).toHaveLength(1);
    expect(bodyRows()[0]).toHaveTextContent("Asha Rao");
  });

  it("offers the full ids behind the truncated ones", async () => {
    renderPage();
    const tips = await screen.findAllByRole("button", { name: "Full settlement and case ids" });
    // Row 2 is MINE — the already-decided APPROVED row leads under the newest-first sort.
    fireEvent.click(tips[1]);
    const tip = await screen.findByRole("tooltip");
    expect(tip).toHaveTextContent(MINE.id);
    expect(tip).toHaveTextContent(MINE.collectionCaseId);
  });
});
