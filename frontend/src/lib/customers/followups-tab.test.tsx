import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customersApi, leadsApi, type CallLogView, type CustomerDetail } from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

const roleRef = vi.hoisted(() => ({ role: "ADMIN" }));
vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: roleRef.role, realRole: roleRef.role }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

const detail = {
  customerId: 42,
  profile: { mobile: "9876543210" },
  applications: [],
  loans: [],
  payments: [],
} as unknown as CustomerDetail;

const log = (id: number, callbackOn: string | null): CallLogView => ({
  id,
  callType: "OUTBOUND",
  outcome: "CALLBACK",
  callbackOn,
  notes: `note-${id}`,
  author: "Asha",
  at: "2026-10-01T05:00:00Z",
  loanId: null,
});

function renderTab(logs: CallLogView[], leadMobile: string | null) {
  vi.spyOn(customersApi, "callLogs").mockResolvedValue(logs);
  vi.spyOn(leadsApi, "list").mockResolvedValue({
    rows: leadMobile ? [{ id: 1, mobile: leadMobile, callStatus: "CALLBACK", leadOutcome: "NEW", createdByStaffName: "Ravi" }] : [],
    page: 1,
    size: 25,
    total: 0,
  } as never);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="followups" detail={detail} customerId={42} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  roleRef.role = "ADMIN";
});

describe("Follow-ups tab", () => {
  it("splits upcoming from past and paints an overdue callback error-700", async () => {
    renderTab([log(1, "2099-01-15"), log(2, "2020-01-15"), log(3, null)], "9876543210");
    expect(await screen.findByText("note-1")).toBeTruthy();
    expect(screen.queryByText("note-3")).toBeNull();
    const overdue = screen.getByText("note-2").closest("tr")!;
    expect(overdue.querySelector("td.text-error-700")).not.toBeNull();
    const upcoming = screen.getByText("note-1").closest("tr")!;
    expect(upcoming.querySelector("td.text-error-700")).toBeNull();
    expect(upcoming.textContent).toContain("Done");
    expect(await screen.findByText("Added by Ravi")).toBeTruthy();
  });

  it("does not call the lead list for roles without leads:manage", async () => {
    roleRef.role = "CREDIT_EXECUTIVE";
    renderTab([], "9876543210");
    expect(await screen.findByText("Telecalling status is visible to telecallers")).toBeTruthy();
    expect(leadsApi.list).not.toHaveBeenCalled();
  });

  it("says there is no lead when the mobile does not match exactly, and empty state without callbacks", async () => {
    renderTab([], "9999999999");
    expect(await screen.findByText("No telecalling lead on file")).toBeTruthy();
    expect(screen.getByText("No follow-up scheduled for this customer.")).toBeTruthy();
  });
});
