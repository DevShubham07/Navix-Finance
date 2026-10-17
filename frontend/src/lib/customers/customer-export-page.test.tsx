/**
 * `/staff/customers/[customerId]` → Export: the full-profile CSV/PDF menu is ADMIN-only. It stands
 * on `customer:manage`, so a real ADMIN working as Admin sees it and a Credit Executive (or an ADMIN
 * working as one) does not. The tabs, dialogs and the credit gauge have their own tests; stubbed.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { can, type StaffRole } from "@/lib/auth/rbac";
import { customersApi, staffApi, type CustomerDetail } from "@/lib/api/applications";
import CustomerDetailPage from "@/app/staff/customers/[customerId]/page";

const session = vi.hoisted(() => ({ role: "ADMIN" as string }));

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
vi.mock("@/components/staff/pipeline/hooks", async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  useStaffMe: () => ({ data: { id: "1", name: "Meera Krishnan", role: session.role, realRole: session.role } }),
  useCan: () => (p: Parameters<typeof can>[2]) => can(session.role as StaffRole, session.role as StaffRole, p),
}));
vi.mock("@/components/staff/customer-tabs", () => ({ CUSTOMER_TABS: [], CustomerTabBody: () => null }));
vi.mock("@/components/staff/application-detail-dialog", () => ({ ApplicationDetailDialog: () => null }));
vi.mock("@/components/staff/credit-score-gauge", () => ({ CreditScoreGauge: () => null }));

const detail: CustomerDetail = {
  customerId: 42,
  profile: { fullName: "Asha Verma", monthlySalaryPaise: 4_200_000 } as CustomerDetail["profile"],
  applications: [{ id: 318, status: "ACTIVE", loanId: 91 } as CustomerDetail["applications"][number]],
  loans: [],
  payments: [],
  limitOverridePaise: null,
};

async function renderPage(role: string) {
  session.role = role;
  vi.spyOn(customersApi, "get").mockResolvedValue(detail);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerDetailPage />
    </QueryClientProvider>,
  );
  await screen.findByRole("heading", { name: "Asha Verma" });
}

describe("customer full-profile export", () => {
  it("offers an ADMIN the CSV and PDF options, fetching the export-only extras on open", async () => {
    const documents = vi.spyOn(customersApi, "documents").mockResolvedValue([]);
    const verifications = vi.spyOn(staffApi, "verifications").mockResolvedValue([]);
    const references = vi.spyOn(staffApi, "references").mockResolvedValue([]);
    await renderPage("ADMIN");

    // Nothing export-only is fetched until someone reaches for the menu.
    expect(documents).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: /Export/ }));

    expect(await screen.findByRole("button", { name: "CSV — full profile" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "PDF — full profile" })).toBeInTheDocument();
    await vi.waitFor(() => expect(documents).toHaveBeenCalledWith(42));
    expect(verifications).toHaveBeenCalledWith(318);
    expect(references).toHaveBeenCalledWith(318);
  });

  it("shows no Export control to a CREDIT_EXECUTIVE", async () => {
    await renderPage("CREDIT_EXECUTIVE");
    expect(screen.getByRole("button", { name: /Refresh/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Export/ })).not.toBeInTheDocument();
  });
});
