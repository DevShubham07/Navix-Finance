import { render, screen, within } from "@testing-library/react";
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

describe("Repayment tab - Collection and profitability", () => {
  it("lays out server-derived business figures", async () => {
    role = "ADMIN";
    renderRepay("OVERDUE", [{ ...pending, id: 11, status: "VERIFIED", amountPaise: 100_000, paidOn: "2026-10-05" } as PaymentView], "2026-10-01", {
      penaltyPaise: 90_000, penaltyDays: 3, outstandingPaise: 595_000, verifiedPaise: 100_000,
    });
    expect(await screen.findByText("Collection & profitability")).toBeInTheDocument();
    expect(screen.getByText("3 / 30")).toBeInTheDocument();
    // 5,000 principal * 2% * 27 days = 2,700
    expect(screen.getByText("₹2,700")).toBeInTheDocument();
    expect(screen.getByText("15% of ₹6,650")).toBeInTheDocument();
    expect(screen.getByText("Recovered vs disbursed").parentElement).toHaveTextContent("₹3,410");
  });
});

describe("Repayment tab - closed loan figures", () => {
  it("penalty headroom is a dash on a closed loan", async () => {
    role = "ADMIN";
    renderRepay("CLOSED");
    await screen.findByText("Collection & profitability");
    expect(screen.getByText("Penalty headroom").parentElement).toHaveTextContent("—");
  });
});

describe("Repayment tab - colours by meaning", () => {
  it("penalty headroom is plain ink on an active, not-overdue loan", async () => {
    role = "ADMIN";
    renderRepay("ACTIVE");
    await screen.findByText("Collection & profitability");
    expect(screen.getByText("Penalty headroom").nextElementSibling).not.toHaveClass("text-error-700");
    expect(screen.getByText("Loan number")).toHaveClass("text-info-500");
    expect(screen.getByText("Tenure").nextElementSibling).toHaveClass("text-warning-800");
  });

  it("penalty headroom is error once the loan is overdue", async () => {
    role = "ADMIN";
    renderRepay("OVERDUE", [], "2026-10-01", { penaltyPaise: 90_000, penaltyDays: 3 });
    await screen.findByText("Collection & profitability");
    expect(screen.getByText("Penalty headroom").nextElementSibling).toHaveClass("text-error-700");
  });
});

describe("Repayment tab - payment amount colours", () => {
  it("colours the amount by payment status", async () => {
    role = "ADMIN";
    renderRepay("ACTIVE", [
      { ...pending, id: 1, status: "VERIFIED", amountPaise: 111_100 } as PaymentView,
      { ...pending, id: 2, status: "PENDING_VERIFICATION", amountPaise: 222_200 } as PaymentView,
      { ...pending, id: 3, status: "REJECTED", amountPaise: 333_300 } as PaymentView,
    ]);
    const pay = within((await screen.findByText("Payments")).parentElement!.parentElement!);
    expect(await pay.findByText("₹1,111")).toHaveClass("text-success-700");
    expect(pay.getByText("₹2,222")).toHaveClass("text-warning-800");
    expect(pay.getByText("₹3,333")).toHaveClass("text-error-700");
  });
});
