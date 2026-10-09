import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApplicationApiError, collectionsApi, customersApi,
  type ApplicationView, type CaseDetailView, type CustomerDetail, type LoanView,
} from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

let role = "COLLECTION_EXECUTIVE";
vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role, realRole: role }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

const loan = {
  id: 77, customerId: 42, principalPaise: 500_000, processingFeePaise: 50_000, gstPaise: 9_000,
  netDisbursedPaise: 441_000, dailyInterestRate: 0.01, disbursedOn: "2026-09-01", dueDate: "2026-10-05",
  totalRepayablePaise: 665_000, outstandingPaise: 505_000, status: "OVERDUE", disbursalTxnRef: "T", closedOn: null,
} as LoanView;
const CASE: CaseDetailView = {
  id: "case-77", loanId: 77, assignedOfficerId: 5, assignedOfficerName: "Officer",
  createdAt: "2026-10-01T10:00:00Z", dpd: 4, bucket: "T0_T7", loan: null,
};

function renderTab(l: LoanView = loan) {
  const detail = {
    customerId: 42, profile: null, loans: [l], payments: [],
    applications: [{ id: 318, status: "OVERDUE", loanId: 77 } as ApplicationView],
  } as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="collections" detail={detail} customerId={42} app={detail.applications[0]} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  role = "COLLECTION_EXECUTIVE";
  vi.spyOn(collectionsApi, "caseByLoan").mockResolvedValue(CASE);
  vi.spyOn(collectionsApi, "listInteractions").mockResolvedValue([]);
  vi.spyOn(collectionsApi, "listCasePayments").mockResolvedValue([]);
  vi.spyOn(collectionsApi, "listOfficers").mockResolvedValue([
    { id: 5, name: "Asha", role: "COLLECTION_EXECUTIVE", active: true },
    { id: 6, name: "Gone", role: "COLLECTION_EXECUTIVE", active: false },
  ]);
  vi.spyOn(customersApi, "callLogs").mockResolvedValue([]);
  vi.spyOn(customersApi, "documents").mockResolvedValue([]);
});
afterEach(() => vi.restoreAllMocks());

describe("Collections tab", () => {
  it("PTP field only for PROMISE_TO_PAY, warns when empty, hidden date is not sent", async () => {
    const log = vi.spyOn(collectionsApi, "logInteraction").mockResolvedValue({} as never);
    renderTab();
    const outcome = await screen.findByLabelText("Outcome");
    expect(screen.queryByLabelText("Promise-to-pay")).toBeNull();
    fireEvent.change(outcome, { target: { value: "PROMISE_TO_PAY" } });
    const date = screen.getByLabelText("Promise-to-pay");
    expect(screen.getByText(/No promise-to-pay date/)).toBeInTheDocument();
    fireEvent.change(date, { target: { value: "2026-10-12" } });
    expect(screen.queryByText(/No promise-to-pay date/)).toBeNull();
    fireEvent.change(outcome, { target: { value: "NO_ANSWER" } });
    fireEvent.click(screen.getByRole("button", { name: "Log" }));
    await waitFor(() => expect(log).toHaveBeenCalledTimes(1));
    expect(log.mock.calls[0][1]).toEqual({ type: "CALL", outcome: "NO_ANSWER", promiseToPayDate: undefined, proofRef: undefined });
  });

  it("settlement: Propose disabled until positive, sends paise", async () => {
    const propose = vi.spyOn(collectionsApi, "proposeSettlement").mockResolvedValue({ settlementAmountPaise: 1_050 } as never);
    renderTab();
    const amount = await screen.findByLabelText("Settlement amount (₹)");
    const button = screen.getByRole("button", { name: "Propose" });
    fireEvent.change(amount, { target: { value: "." } });
    expect(button).toBeDisabled();
    fireEvent.change(amount, { target: { value: "0" } });
    expect(button).toBeDisabled();
    fireEvent.change(amount, { target: { value: "10.5.09" } });
    expect(amount).toHaveValue("10.50");
    fireEvent.click(button);
    await waitFor(() => expect(propose).toHaveBeenCalledWith("case-77", 1_050));
  });

  it("payment amount follows the same rule and is sent in paise", async () => {
    const raise = vi.spyOn(collectionsApi, "raisePayment").mockResolvedValue({ amountPaise: 250_075, status: "PENDING_ACCOUNTANT" } as never);
    renderTab();
    const amount = await screen.findByLabelText("Amount (₹)");
    const button = screen.getByRole("button", { name: "Record payment" });
    expect(button).toBeDisabled();
    fireEvent.change(amount, { target: { value: "₹2,500.759" } });
    expect(amount).toHaveValue("2500.75");
    fireEvent.click(button);
    await waitFor(() => expect(raise).toHaveBeenCalledTimes(1));
    expect(raise.mock.calls[0][1]).toMatchObject({ kind: "PART_PAYMENT", amountPaise: 250_075 });
  });

  it("header shows officer, cycle and an overdue promise-to-pay", async () => {
    vi.spyOn(collectionsApi, "listInteractions").mockResolvedValue([
      { id: "i1", collectionCaseId: "case-77", type: "CALL", outcome: "PROMISE_TO_PAY", promiseToPayDate: "2020-01-02", proofRef: null, loggedAt: "2026-10-08T10:00:00Z" },
    ]);
    renderTab();
    expect(await screen.findByText("Officer")).toBeInTheDocument();
    expect(screen.getByText("1st advance")).toBeInTheDocument();
    expect(await screen.findByText("overdue")).toBeInTheDocument();
  });

  it("does not open a case just by viewing; the explicit button does", async () => {
    role = "COLLECTION_HEAD";
    vi.spyOn(collectionsApi, "caseByLoan").mockResolvedValue(null);
    const open = vi.spyOn(collectionsApi, "openCase").mockResolvedValue(CASE);
    renderTab();
    const btn = await screen.findByRole("button", { name: /Open collection case/ });
    expect(open).not.toHaveBeenCalled();
    fireEvent.click(btn);
    await waitFor(() => expect(open).toHaveBeenCalledWith(77));
  });

  it("CASE_NOT_ASSIGNED shows a neutral notice", async () => {
    vi.spyOn(collectionsApi, "caseByLoan").mockRejectedValue(new ApplicationApiError("no", "CASE_NOT_ASSIGNED", 403));
    role = "COLLECTION_EXECUTIVE";
    renderTab();
    expect(await screen.findByText(/assigned to another officer/)).toBeInTheDocument();
  });

  it("assign is for the head/admin and lists ACTIVE officers only", async () => {
    role = "COLLECTION_HEAD";
    renderTab();
    expect(await screen.findByLabelText("Officer (active executives)")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("Asha (COLLECTION_EXECUTIVE)")).toBeInTheDocument());
    expect(screen.queryByText(/Gone/)).toBeNull();
  });

  it("executive can log and record but not assign", async () => {
    role = "COLLECTION_EXECUTIVE";
    role = "COLLECTION_EXECUTIVE";
    renderTab();
    expect(await screen.findByLabelText("Outcome")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Record payment" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Officer (active executives)")).toBeNull();
  });

  it("splits calls about this loan from other calls", async () => {
    vi.spyOn(customersApi, "callLogs").mockResolvedValue([
      { id: 1, callType: "OUTBOUND", outcome: "CONNECTED", callbackOn: null, notes: "a", author: "x", at: null, loanId: 77 },
      { id: 2, callType: "INBOUND", outcome: "REFUSED", callbackOn: null, notes: "b", author: "x", at: null, loanId: null },
    ]);
    renderTab();
    expect(await screen.findByText("Calls about this loan (1)")).toBeInTheDocument();
    expect(screen.getByText("Other customer calls (1)")).toBeInTheDocument();
  });

  it("closed loan: DPD stops at closedOn, not the server case dpd", async () => {
    vi.spyOn(collectionsApi, "caseByLoan").mockResolvedValue({ ...CASE, dpd: 99, bucket: "T90_PLUS" });
    renderTab({ ...loan, status: "CLOSED", closedOn: "2026-10-07" } as LoanView);
    expect(await screen.findByText(/2 days/)).toBeInTheDocument();
    expect(screen.queryByText(/99/)).toBeNull();
  });

  it("case status says Open (not No case) when the case is another officer's", async () => {
    vi.spyOn(collectionsApi, "caseByLoan").mockRejectedValue(new ApplicationApiError("no", "CASE_NOT_ASSIGNED", 403));
    renderTab();
    await screen.findByText(/assigned to another officer/);
    expect(screen.queryByText("No case")).toBeNull();
    expect(screen.getByText("Open")).toBeInTheDocument();
  });
});

describe("Collections section tone", () => {
  const sectionOf = () => screen.getByText("Collections").parentElement!.parentElement!;

  it.each([
    ["DPD 35", 35, "OVERDUE", "border-error-100"],
    ["DPD 5", 5, "OVERDUE", "border-warning-100"],
  ])("%s tints the section", async (_n, dpd, status, cls) => {
    vi.spyOn(collectionsApi, "caseByLoan").mockResolvedValue({ ...CASE, dpd });
    renderTab({ ...loan, status } as LoanView);
    await screen.findByText("Interactions");
    expect(sectionOf()).toHaveClass(cls);
  });

  it.each(["CLOSED", "REPAID"])("a %s loan is success-tinted", async (status) => {
    renderTab({ ...loan, status, closedOn: "2026-10-05" } as LoanView);
    await screen.findByText("Interactions");
    expect(sectionOf()).toHaveClass("border-success-100");
  });
});
