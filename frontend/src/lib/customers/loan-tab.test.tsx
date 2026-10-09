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
});
