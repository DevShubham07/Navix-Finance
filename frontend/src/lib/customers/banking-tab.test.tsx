import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customersApi, type ApplicationView, type CustomerDetail } from "@/lib/api/applications";
import { bankAnalysisApi, type BankAnalysisView } from "@/lib/api/bank-analysis";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

function renderTab() {
  vi.spyOn(customersApi, "documents").mockResolvedValue([] as never);
  const app = { id: 318, status: "ACTIVE" } as unknown as ApplicationView;
  const detail = {
    customerId: 42,
    profile: { salaryBank: "HDFC", monthlySalaryPaise: 5000000 },
    applications: [app],
    loans: [],
    payments: [],
  } as unknown as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="banking" detail={detail} customerId={42} app={app} applicationId={318} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Banking tab", () => {
  it("shows the pending empty state and declared-only salary when no analysis exists", async () => {
    renderTab();
    expect(await screen.findByText("Analysis pending", { selector: "h3, p, div" })).toBeInTheDocument();
    expect(screen.getByText("Declared only")).toBeInTheDocument();
  });

  it("renders the ACCEPT banner and account chip for an analysed payload", async () => {
    const view: BankAnalysisView = {
      status: "ANALYSED",
      bank: "HDFC BANK",
      accounts: [{ maskedNumber: "XXXX1154", type: "SAVINGS", txnCount: 309, status: "ACTIVE" }],
      salary: {
        verdict: "ACCEPT",
        rule: "3 consecutive months of salary credits",
        primarySource: "NEFT",
        consecutiveMonths: 3,
        minSalaryPaise: 4500000,
        uniqueCredits: 3,
        analysedAt: "2026-10-08T10:00:00Z",
      },
    };
    vi.spyOn(bankAnalysisApi, "get").mockResolvedValue(view);
    renderTab();
    expect(await screen.findByText("3 consecutive months of salary credits")).toBeInTheDocument();
    expect(screen.getByText("ACCEPT")).toBeInTheDocument();
    expect(screen.getByText("XXXX1154 · SAVINGS · 309 txns")).toBeInTheDocument();
    expect(screen.queryByText("Declared only")).not.toBeInTheDocument();
  });
});
