/**
 * `/staff/customers/[customerId]` admin cards (Phase 2 polish):
 *  - limit + approved-amount cards carry a basis badge (Admin override vs Salary rule);
 *  - the salary inputs show a ₹ prefix and a live grouped preview, without changing the payload;
 *  - the salary-day card warns when its projected due date breaks the 40-day rule.
 *
 * The tabs, dialogs and the credit gauge have their own tests; they are stubbed here.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customersApi, type ApplicationView, type CustomerDetail } from "@/lib/api/applications";
import CustomerDetailPage from "@/app/staff/customers/[customerId]/page";

const loanMath = vi.hoisted(() => ({
  override: null as null | ((p: { disbursedOn: Date; salaryDay: number }) => Date),
}));

vi.mock("@/lib/calc/loan-math", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/calc/loan-math")>();
  return {
    ...actual,
    dueDateFromSalary: (p: { disbursedOn: Date; salaryDay: number }) =>
      loanMath.override ? loanMath.override(p) : actual.dueDateFromSalary(p),
  };
});
vi.mock("next/navigation", () => ({
  useParams: () => ({ customerId: "42" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  NoAccessNotice: () => null,
  errMessage: (e: unknown) => String(e),
  AdminForceDisbursementAction: () => null,
  SanctionedRejectAction: () => null,
}));
vi.mock("@/components/staff/customer-tabs", () => ({ CUSTOMER_TABS: [], CustomerTabBody: () => null }));
vi.mock("@/components/staff/application-detail-dialog", () => ({ ApplicationDetailDialog: () => null }));
vi.mock("@/components/staff/credit-score-gauge", () => ({ CreditScoreGauge: () => null }));

afterEach(() => {
  loanMath.override = null;
});

const SANCTIONED = {
  id: 318,
  status: "SANCTIONED",
  loanId: null,
  salaryCreditDay: 30,
  eligibleLimitPaise: 1_050_000,
  sanctionedAmountPaise: 1_000_000,
} as unknown as ApplicationView;

function detail(overrides: Partial<CustomerDetail> = {}): CustomerDetail {
  return {
    customerId: 42,
    profile: { fullName: "Asha Verma", monthlySalaryPaise: 4_200_000 } as CustomerDetail["profile"],
    applications: [SANCTIONED],
    loans: [],
    payments: [],
    limitOverridePaise: null,
    ...overrides,
  };
}

async function renderPage(d: CustomerDetail) {
  vi.spyOn(customersApi, "get").mockResolvedValue(d);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerDetailPage />
    </QueryClientProvider>,
  );
  await screen.findByText("Maximum loan amount (admin)");
}

describe("limit basis badge", () => {
  it("reads Salary rule on both the limit and approved-amount cards when no override is set", async () => {
    await renderPage(detail());
    expect(screen.getAllByText("Salary rule")).toHaveLength(2);
    expect(screen.queryByText("Admin override")).not.toBeInTheDocument();
  });

  it("reads Admin override on both cards when an override is stored", async () => {
    await renderPage(detail({ limitOverridePaise: 7_500_000 }));
    expect(screen.getAllByText("Admin override")).toHaveLength(2);
    expect(screen.queryByText("Salary rule")).not.toBeInTheDocument();
  });

  it("leaves the approved-amount card's limit line out when no limit is on file", async () => {
    await renderPage(
      detail({ applications: [{ ...SANCTIONED, eligibleLimitPaise: null } as ApplicationView] }),
    );
    expect(screen.queryByText("Eligible limit:")).not.toBeInTheDocument();
    // Only the limit card's own badge remains.
    expect(screen.getAllByText("Salary rule")).toHaveLength(1);
  });
});

describe("salary inputs", () => {
  it("previews the typed salary with Indian grouping and submits the same digits as before", async () => {
    const update = vi.spyOn(customersApi, "updateProfile").mockResolvedValue({} as never);
    await renderPage(detail());
    expect(screen.getByText("₹ 42,000")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Monthly salary (₹)"), { target: { value: "125000" } });
    expect(screen.getByText("₹ 1,25,000")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Save changes/ }));
    await vi.waitFor(() => expect(update).toHaveBeenCalledTimes(1));
    expect(update.mock.calls[0][1]).toMatchObject({ monthlySalaryPaise: 12_500_000 });
  });
});

describe("salary-day 40-day note", () => {
  it("stays hidden for a projection inside the window, with its live region already mounted", async () => {
    await renderPage(detail());
    expect(screen.queryByText(/beyond the 40-day/)).not.toBeInTheDocument();
    // The region exists before the note does, so the note is announced when it appears.
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });

  it("announces the note in the already-mounted region when a day change pushes past 40 days", async () => {
    await renderPage(detail());
    const region = screen.getByRole("status");
    loanMath.override = ({ disbursedOn }) =>
      new Date(disbursedOn.getFullYear(), disbursedOn.getMonth(), disbursedOn.getDate() + 41);
    fireEvent.change(screen.getByLabelText("Salary credit day"), { target: { value: "5" } });
    expect(region).toHaveTextContent("That due date is 41 days after a disbursal today");
  });

  it("appears when the projected due date is more than 40 days after a disbursal today", async () => {
    loanMath.override = ({ disbursedOn }) =>
      new Date(disbursedOn.getFullYear(), disbursedOn.getMonth(), disbursedOn.getDate() + 45);
    await renderPage(detail());
    expect(screen.getByRole("status")).toHaveTextContent(
      "That due date is 45 days after a disbursal today — beyond the 40-day limit.",
    );
  });
});
