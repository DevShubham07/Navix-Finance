import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { customersApi, staffApi, type ApplicationView, type CustomerDetail, type LoanView, type PaymentView } from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

let role = "ADMIN";
vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role, realRole: role }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

const loan = (status: string, dueDate = "2026-11-10"): LoanView => ({
  id: 208, customerId: 42, principalPaise: 500_000, processingFeePaise: 50_000, gstPaise: 9_000,
  netDisbursedPaise: 441_000, dailyInterestRate: 0.01, disbursedOn: "2026-10-08", dueDate,
  totalRepayablePaise: 665_000, outstandingPaise: status === "CLOSED" ? 0 : 505_000, status,
  disbursalTxnRef: "TXN1", closedOn: null,
});
const pending = { id: 9, loanId: 208, amountPaise: 100_000, method: "UPI", status: "PENDING_VERIFICATION", txnRef: "UPI1", proofUrl: null, paidOn: "2026-10-09", partial: true } as PaymentView;

function renderRepay(status: string, payments: PaymentView[] = [], dueDate?: string, out: object = {}) {
  vi.spyOn(customersApi, "documents").mockResolvedValue([]);
  vi.spyOn(staffApi, "repayments").mockResolvedValue(payments);
  const detail = {
    customerId: 42, profile: null, loans: [loan(status, dueDate)], payments,
    applications: [{ id: 318, status: "ACTIVE", loanId: 208 } as ApplicationView],
    outstandingByLoanId: { "208": { loanId: 208, asOf: "2026-10-09", outstandingPaise: 505_000, interestPaise: 5_000, penaltyPaise: 0, verifiedPaise: 0, ...out } },
  } as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="repayment" detail={detail} customerId={42} app={detail.applications[0]} />
    </QueryClientProvider>,
  );
}

describe("Repayment tab", () => {
  beforeEach(() => {
    role = "ADMIN";
  });

  it("grand total row sums principal and interest", () => {
    renderRepay("ACTIVE");
    const row = screen.getByText("Grand total").closest("tr")!;
    expect(row).toHaveTextContent("₹6,650");
    expect(row).toHaveTextContent("₹5,050");
    expect(screen.getByText(/Pay early/)).toBeInTheDocument();
  });

  it("Verify is hidden in the Admin role (it is the Accountant's step)", async () => {
    renderRepay("ACTIVE", [pending]);
    await screen.findByText("Grand total");
    expect(screen.queryByRole("button", { name: /Verify/ })).not.toBeInTheDocument();
  });

  it("Verify shows for an ACCOUNTANT", async () => {
    role = "ACCOUNTANT";
    renderRepay("ACTIVE", [pending]);
    expect(await screen.findByRole("button", { name: /Verify/ })).toBeInTheDocument();
  });

  it("overdue: penalty row shows, Pay early hidden", () => {
    renderRepay("OVERDUE", [], "2026-10-01", { penaltyPaise: 90_000, penaltyDays: 3, outstandingPaise: 595_000 });
    expect(screen.getByText(/Late penalty .2%/).closest("tr")).toHaveTextContent("₹900");
    expect(screen.queryByText(/Pay early/)).not.toBeInTheDocument();
  });

  it("settled: per-row outstanding is a dash, note shown, hint hidden", () => {
    renderRepay("ACTIVE", [], undefined, { settledAmountPaise: 400_000, outstandingPaise: 400_000 });
    expect(screen.getByText(/Settlement \(approved\)/)).toBeInTheDocument();
    expect(screen.getByText("Principal").closest("tr")).toHaveTextContent("—");
    expect(screen.queryByText(/Pay early/)).not.toBeInTheDocument();
  });

  it("Verify is hidden for a TELECALLER", async () => {
    role = "TELECALLER";
    renderRepay("ACTIVE", [pending]);
    expect(await screen.findByText("UPI1")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Verify/ })).not.toBeInTheDocument();
  });

  it("closed loan hides the Pay early hint", () => {
    renderRepay("CLOSED");
    expect(screen.queryByText(/Pay early/)).not.toBeInTheDocument();
  });

  it("rejected payments show the reason and note; partial gets a badge", async () => {
    const rej = { ...pending, id: 10, status: "REJECTED", partial: true, rejectionReason: "AMOUNT_MISMATCH", rejectionNote: "short by 50" } as PaymentView;
    renderRepay("ACTIVE", [rej]);
    expect(await screen.findByText(/short by 50/)).toBeInTheDocument();
    expect(screen.getByText("Partial")).toBeInTheDocument();
  });
});
