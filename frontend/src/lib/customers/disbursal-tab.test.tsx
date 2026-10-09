import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { staffApi, type ApplicationView, type CustomerDetail, type StepResult } from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
  AssignActions: () => null,
  CreditDecisionActions: () => null,
  SanctionedRejectAction: () => null,
  AdminForceDisbursementAction: () => null,
  DisbursementActions: () => <div>disbursement-actions</div>,
}));

function renderTab(status: string, steps: StepResult[]) {
  const app = {
    id: 318, status, disbursalAccountNumber: "1234567890", disbursalIfsc: "HDFC0001",
    disbursalHolderName: "VIJAY", disbursalBank: "HDFC Bank",
  } as unknown as ApplicationView;
  vi.spyOn(staffApi, "verifications").mockResolvedValue(steps);
  const detail = {
    customerId: 42, profile: null, applications: [app], payments: [],
    loans: [{ id: 208, netDisbursedPaise: 441_000, disbursedOn: "2026-10-08", disbursalTxnRef: "TXN9" }],
  } as unknown as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="disbursal" detail={detail} customerId={42} app={app} applicationId={318} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Disbursal tab", () => {
  it("renders the release actions at DISBURSEMENT_PENDING", async () => {
    renderTab("DISBURSEMENT_PENDING", []);
    expect(await screen.findByText("disbursement-actions")).toBeInTheDocument();
    expect(screen.getByText("1234567890")).toBeInTheDocument();
    expect(screen.getByText("TXN9")).toBeInTheDocument();
    expect(screen.getByText("Not run")).toBeInTheDocument();
  });

  it("shows SUCCESS for a passed penny drop", async () => {
    renderTab("ACTIVE", [
      {
        checkType: "PENNY_DROP", status: "PASS", message: null, provider: "SIGNZY",
        derived: { accountExists: true, beneficiaryName: "VIJAY", nameMatch: 1 },
      } as StepResult,
    ]);
    expect(await screen.findByText("SUCCESS")).toBeInTheDocument();
    expect(screen.getByText("100%")).toBeInTheDocument();
    expect(screen.queryByText("disbursement-actions")).not.toBeInTheDocument();
  });

  it("shows RRN and the manual-override note from the penny-drop derived keys", async () => {
    renderTab("DISBURSEMENT_PENDING", [
      {
        checkType: "PENNY_DROP", status: "PASS", provider: "SIGNZY",
        derived: { bankRrn: "RRN-77", manualBy: "Credit Reviewer", manualAt: "2026-10-08T05:30:00Z" },
      } as unknown as StepResult,
    ]);
    expect(await screen.findByText("RRN-77")).toBeInTheDocument();
    expect(screen.getByText(/Manually overridden by Credit Reviewer/)).toBeInTheDocument();
  });
});
