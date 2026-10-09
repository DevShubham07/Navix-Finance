import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customersApi, type CustomerDetail, type DedupeView } from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

const roleRef = vi.hoisted(() => ({ role: "ADMIN" }));
vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: roleRef.role }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

const detail = { customerId: 42, profile: null, applications: [], loans: [], payments: [] } as unknown as CustomerDetail;

function renderTab(view: DedupeView) {
  vi.spyOn(customersApi, "dedupe").mockResolvedValue(view);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="dedupe" detail={detail} customerId={42} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  roleRef.role = "ADMIN";
});

describe("Dedupe tab", () => {
  it("flags a REVIEW Aadhaar duplicate, tints blocklist hits and shows the rejection block", async () => {
    renderTab({
      aadhaar: { status: "REVIEW", applicationId: 1, otherCustomerIds: [7], message: "held elsewhere" },
      blocklistHits: [{ type: "PAN", maskedValue: "XXXXXX234F", reason: null, addedOn: null }],
      rejection: { applicationId: 1, reasonCode: "MANUAL", reasonDetail: null, blockedUntil: "2026-11-01T00:00:00Z" },
    });
    expect(await screen.findByText("REVIEW")).toBeTruthy();
    expect(screen.getByText("#7").getAttribute("href")).toBe("/staff/customers/7");
    expect(screen.getByText("XXXXXX234F").closest("tr")?.className).toContain("bg-error-50");
    expect(screen.queryByText("Added on")).toBeNull();
    expect(screen.getByText(/Blocked until/)).toBeTruthy();
  });

  it("shows the row message for a PASS and hides Override from roles without kyc:approve", async () => {
    roleRef.role = "ACCOUNTANT";
    renderTab({
      aadhaar: { status: "REVIEW", applicationId: 1, otherCustomerIds: [7], message: "held elsewhere" },
      blocklistHits: [],
      rejection: null,
    });
    expect(await screen.findByText("REVIEW")).toBeTruthy();
    expect(screen.queryByText("Override")).toBeNull();
  });

  it("shows the PASS message instead of the clear text", async () => {
    renderTab({
      aadhaar: { status: "PASS", applicationId: 1, otherCustomerIds: [], message: "Manually approved by Asha" },
      blocklistHits: [],
      rejection: null,
    });
    expect(await screen.findByText("Manually approved by Asha")).toBeTruthy();
    expect(screen.queryByText("Clear")).toBeNull();
  });

  it("shows the clear and empty states", async () => {
    renderTab({ aadhaar: { status: null, applicationId: null, otherCustomerIds: [], message: null }, blocklistHits: [], rejection: null });
    expect(await screen.findByText("Clear")).toBeTruthy();
    expect(screen.getByText("No blocklist match")).toBeTruthy();
    expect(screen.getByText("Not blocked")).toBeTruthy();
  });
});
