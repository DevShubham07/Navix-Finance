import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  customersApi,
  staffApi,
  type ApplicationView,
  type CustomerDetail,
  type DocumentView,
} from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
  AssignActions: () => <div>assign-actions</div>,
  CreditDecisionActions: () => <div>credit-decision-actions</div>,
  SanctionedRejectAction: () => <div>sanctioned-reject</div>,
  AdminForceDisbursementAction: () => <div>force-disbursement</div>,
  DisbursementActions: () => <div>disbursement-actions</div>,
}));

const doc = (id: number, docType: string): DocumentView =>
  ({ id, docType, fileName: `${docType}.pdf`, uploadedAt: "2026-10-08T16:29:00Z" }) as DocumentView;

function renderTab(status: string, docs: DocumentView[], events: unknown[] = []) {
  const app = {
    id: 318, status, sanctionedAmountPaise: 600_000, approvedRepaymentDate: "2026-11-10",
    amountRequestedPaise: 500_000, salaryCreditDay: 10,
  } as unknown as ApplicationView;
  vi.spyOn(customersApi, "documents").mockResolvedValue([
    { applicationId: 318, applicationStatus: status, documents: docs },
  ] as never);
  vi.spyOn(staffApi, "events").mockResolvedValue(events as never);
  vi.spyOn(staffApi, "verifications").mockResolvedValue([]);
  const detail = { customerId: 42, profile: null, applications: [app], loans: [], payments: [] } as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="sanction" detail={detail} customerId={42} app={app} applicationId={318} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Sanction tab", () => {
  it("shows the credit decision actions at CREDIT_EXEC_PENDING", async () => {
    renderTab("CREDIT_EXEC_PENDING", []);
    expect(await screen.findByText("credit-decision-actions")).toBeInTheDocument();
  });

  it("has no action cluster once ACTIVE", async () => {
    renderTab("ACTIVE", []);
    expect(await screen.findByText("Decision")).toBeInTheDocument();
    expect(screen.queryByText("credit-decision-actions")).not.toBeInTheDocument();
  });

  it("letter pill: Not generated", async () => {
    renderTab("SANCTIONED", []);
    expect(await screen.findByText("Not generated")).toBeInTheDocument();
  });

  it("letter pill: Awaiting signature with a dashed empty card", async () => {
    renderTab("SANCTIONED", [doc(1, "SANCTION_LETTER")]);
    expect(await screen.findByText(/Borrower has not signed yet/)).toBeInTheDocument();
    expect(screen.getAllByText("Awaiting signature").length).toBeGreaterThan(0);
  });

  it("letter pill: Signed by borrower", async () => {
    renderTab("SANCTIONED", [doc(1, "SANCTION_LETTER"), doc(2, "SIGNED_AGREEMENT")]);
    expect(await screen.findByText("Signed by borrower")).toBeInTheDocument();
    expect(screen.getByText("Borrower-signed copy")).toBeInTheDocument();
  });

  it("does not show borrower acceptance for an admin force-disburse", async () => {
    renderTab("DISBURSEMENT_PENDING", [], [
      { id: 1, action: "ADMIN_FORCE_DISBURSE", fromStatus: "SANCTIONED", toStatus: "DISBURSEMENT_PENDING", at: "2026-10-08T10:00:00Z" },
    ]);
    expect(await screen.findByText("Sanction letter")).toBeInTheDocument();
    expect(screen.queryByText("Borrower acceptance")).not.toBeInTheDocument();
  });

  it("shows borrower acceptance for ACCEPT_OFFER", async () => {
    renderTab("DISBURSEMENT_PENDING", [], [
      { id: 1, action: "ACCEPT_OFFER", fromStatus: "SANCTIONED", toStatus: "DISBURSEMENT_PENDING", at: "2026-10-08T10:00:00Z" },
    ]);
    expect(await screen.findByText("Borrower acceptance")).toBeInTheDocument();
  });
});
