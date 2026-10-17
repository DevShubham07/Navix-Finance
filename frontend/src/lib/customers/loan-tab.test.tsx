import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { customersApi, type ApplicationView, type CustomerDetail, type LoanView } from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

const loan = (status: string): LoanView => ({
  id: 208, customerId: 42, principalPaise: 500_000, processingFeePaise: 50_000, gstPaise: 9_000,
  netDisbursedPaise: 441_000, dailyInterestRate: 0.01, disbursedOn: "2026-10-08", dueDate: "2026-11-10",
  totalRepayablePaise: 665_000, outstandingPaise: status === "CLOSED" ? 0 : 505_000, status,
  disbursalTxnRef: "TXN1", closedOn: status === "CLOSED" ? "2026-10-09" : null,
});
const app = (over: Partial<ApplicationView> = {}) =>
  ({ id: 318, status: "ACTIVE", loanId: 208, amountRequestedPaise: 500_000, eligibleLimitPaise: 890_000, salaryCreditDay: 10, ...over }) as ApplicationView;

function renderLoan(d: Partial<CustomerDetail>, onTabChange = vi.fn()) {
  vi.spyOn(customersApi, "documents").mockResolvedValue([]);
  const detail = { customerId: 42, profile: null, applications: [app()], loans: [], payments: [], ...d } as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="loan" detail={detail} customerId={42} app={detail.applications[0] ?? null} onTabChange={onTabChange} />
    </QueryClientProvider>,
  );
  return onTabChange;
}

describe("Loan tab", () => {
  it("active loan: strip shows the amount due; no Pay early hint here", () => {
    renderLoan({
      loans: [loan("ACTIVE")],
      outstandingByLoanId: { "208": { loanId: 208, asOf: "2026-10-09", outstandingPaise: 505_000, interestPaise: 5_000, penaltyPaise: 0, interestDays: 1 } },
    });
    expect(screen.getAllByText("Amount due today")[0].nextSibling).toHaveTextContent("₹5,050");
    expect(screen.getAllByText("1 day × 1%").length).toBeGreaterThan(0);
    expect(screen.queryByText(/Pay early/)).not.toBeInTheDocument();
  });

  it("closed loan: ₹0 due and no overdue chip", () => {
    renderLoan({ loans: [loan("CLOSED")] });
    expect(screen.getAllByText("Amount due today")[0].nextSibling).toHaveTextContent("₹0");
    expect(screen.queryByText(/^\+\d+d$/)).not.toBeInTheDocument();
  });

  it("no loan: empty state routes to the Sanction tab", () => {
    const onTabChange = renderLoan({ applications: [app({ loanId: null, status: "CREDIT_EXEC_PENDING" })] });
    expect(screen.getByText(/No loan on this application yet/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Go to Sanction/ }));
    expect(onTabChange).toHaveBeenCalledWith("sanction");
  });

  it("has no Open loan details button (the pop-up is the loan view)", () => {
    renderLoan({ loans: [loan("ACTIVE")] });
    expect(screen.queryByRole("button", { name: "Open loan details" })).not.toBeInTheDocument();
  });

  it("loan pills carry the repayment behaviour as a colour class + tooltip, with a legend", () => {
    const early: LoanView = { ...loan("CLOSED"), id: 169, dueDate: "2026-06-30", closedOn: "2026-06-25" };
    const late: LoanView = { ...loan("CLOSED"), id: 170, dueDate: "2026-07-30", closedOn: "2026-08-03" };
    renderLoan({ loans: [loan("ACTIVE"), early, late], applications: [app()] });
    const earlyPill = screen.getByTitle("Loan #169 · Closed — Paid 5 days early");
    expect(earlyPill).toHaveClass("cal-preset", "lb-early");
    expect(screen.getByTitle("Loan #170 · Closed — Paid 4 days late")).toHaveClass("lb-late");
    expect(screen.getByTitle(/^Loan #208 · Active — /)).toHaveClass("cal-preset", "on");
    expect(screen.getByLabelText("Loan pill colour legend")).toHaveTextContent("early · ● on time · ● late · ● very late/settled · ● open · ● overdue");
  });

  it("a settled loan's pill is very late / Settled", () => {
    const settled: LoanView = { ...loan("CLOSED"), id: 171, closedOn: "2026-10-01" };
    renderLoan({
      loans: [loan("ACTIVE"), settled],
      outstandingByLoanId: { "171": { loanId: 171, asOf: "2026-10-01", outstandingPaise: 0, settledAmountPaise: 450_000, interestPaise: 100_000, penaltyPaise: 20_000, verifiedPaise: 450_000 } },
    });
    expect(screen.getByTitle("Loan #171 · Closed — Settled")).toHaveClass("lb-verylate");
  });

  it("Earned from this customer sums fees + received interest/penalty, excludes GST, and flags waivers", () => {
    // Loan 208 (active, nothing received yet) + loan 300 settled at ₹5,500 against ₹6,650 of dues.
    const settled: LoanView = { ...loan("CLOSED"), id: 300, closedOn: "2026-10-01", disbursedOn: "2026-08-01", dueDate: "2026-09-10" };
    renderLoan({
      loans: [loan("ACTIVE"), settled],
      payments: [{ id: 1, loanId: 300, amountPaise: 550_000, method: "UPI", status: "VERIFIED", txnRef: null, proofUrl: null, paidOn: "2026-10-01", partial: false }],
      outstandingByLoanId: {
        "208": { loanId: 208, asOf: "2026-10-09", outstandingPaise: 505_000, interestPaise: 5_000, penaltyPaise: 0, verifiedPaise: 0 },
        "300": { loanId: 300, asOf: "2026-10-01", outstandingPaise: 0, settledAmountPaise: 550_000, interestPaise: 150_000, penaltyPaise: 15_000, verifiedPaise: 550_000 },
      },
    });
    expect(screen.getByText("Earned from this customer")).toBeInTheDocument();
    expect(screen.getByText("Processing fees").nextSibling).toHaveTextContent("₹1,000"); // 2 × ₹500, no GST
    expect(screen.getByText("Penalty received").nextSibling).toHaveTextContent("₹150"); // ₹500 above principal → penalty first
    expect(screen.getByText("Interest received").nextSibling).toHaveTextContent("₹350");
    expect(screen.getByText("Total earned").nextSibling).toHaveTextContent("₹1,500");
    expect(screen.getByText(/Across 2 loans · ₹5,050 still outstanding/)).toBeInTheDocument();
    expect(screen.getByText("₹1,150 waived via settlement")).toBeInTheDocument();
  });

  it("no loans: no earnings section", () => {
    renderLoan({ applications: [app({ loanId: null, status: "CREDIT_EXEC_PENDING" })] });
    expect(screen.queryByText("Earned from this customer")).not.toBeInTheDocument();
  });
});
