/** The 17-tab registry: order, the disabled Mandate tab, and every body rendering for a lead-only customer. */
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { customersApi, type CustomerDetail } from "@/lib/api/applications";
import { CUSTOMER_TABS, CustomerTabBody } from "@/components/staff/customer-tabs";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
  AssignActions: () => null,
  CreditDecisionActions: () => null,
  DisbursementActions: () => null,
  AdminForceDisbursementAction: () => null,
  SanctionedRejectAction: () => null,
}));
vi.mock("@/components/staff/verification-checks", () => ({ VerificationChecksPanel: () => null }));
vi.mock("@/components/staff/skip-trace-panel", () => ({ SkipTracePanel: () => null }));
vi.mock("@/components/staff/event-timeline", () => ({ EventTimeline: () => null }));
vi.mock("@/components/staff/journey-stepper", () => ({ JourneyStepper: () => null }));

const KEYS = [
  "customer", "loan", "sanction", "disbursal", "repayment", "banking", "credit", "journey", "references",
  "documents", "addresses", "dedupe", "communication", "activity", "followups", "mandate", "verifications",
];

describe("CUSTOMER_TABS registry", () => {
  it("has the 17 tabs in lifecycle order, Verifications last", () => {
    expect(CUSTOMER_TABS.map((t) => t.key)).toEqual(KEYS);
    expect(CUSTOMER_TABS.at(-1)?.key).toBe("verifications");
  });

  it("disables only Mandate", () => {
    expect(CUSTOMER_TABS.filter((t) => t.disabled).map((t) => t.key)).toEqual(["mandate"]);
  });
});

describe("CustomerTabBody for a customer with no application", () => {
  const detail: CustomerDetail = {
    customerId: 7,
    profile: null,
    applications: [],
    loans: [],
    payments: [],
  };

  it.each(KEYS)("renders %s without throwing", (key) => {
    vi.spyOn(customersApi, "documents").mockResolvedValue([]);
    vi.spyOn(customersApi, "activity").mockResolvedValue([]);
    vi.spyOn(customersApi, "callLogs").mockResolvedValue([]);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    expect(() =>
      render(
        <QueryClientProvider client={qc}>
          <CustomerTabBody tab={key} detail={detail} customerId={7} applicationId={null} app={null} />
        </QueryClientProvider>,
      ),
    ).not.toThrow();
  });
});
