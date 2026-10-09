import { cleanup, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { customersApi, type ApplicationView, type CustomerDetail } from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

let role = "ADMIN";
vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role, realRole: role }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

function renderTab(groups?: unknown) {
  const d = (id: number, docType: string) => ({
    id, docType, fileName: `${docType}.pdf`, uploadedAt: "2026-10-08T10:00:00Z",
  });
  vi.spyOn(customersApi, "documents").mockResolvedValue((groups as never) ?? [
    { applicationId: 318, applicationStatus: "ACTIVE", documents: [d(1, "PAN_CARD_FRONT"), d(2, "SANCTION_LETTER")] },
    { applicationId: 200, applicationStatus: "CLOSED", documents: [d(3, "SALARY_SLIP")] },
  ] as never);
  const app = { id: 318, status: "ACTIVE" } as unknown as ApplicationView;
  const detail = { customerId: 42, profile: null, applications: [app], loans: [], payments: [] } as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="documents" detail={detail} customerId={42} app={app} applicationId={318} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  role = "ADMIN";
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Documents tab", () => {
  it("groups documents into application collapsibles of cards", async () => {
    renderTab();
    expect((await screen.findAllByText(/Application #318/)).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Application #200/).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("button", { name: "View document" }).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByLabelText("Delete document").length).toBeGreaterThanOrEqual(2);
  });

  it("offers Delete only to ADMIN", async () => {
    role = "CREDIT_EXECUTIVE";
    renderTab();
    expect((await screen.findAllByText(/Application #318/)).length).toBeGreaterThan(0);
    expect(screen.queryByLabelText("Delete document")).not.toBeInTheDocument();
  });

  it("numbers customer documents in display (grouped-by-type) order", async () => {
    const d = (id: number, docType: string) => ({ id, docType, fileName: "f.pdf", uploadedAt: "2026-10-08T10:00:00Z" });
    renderTab([
      { applicationId: 2, applicationStatus: "ACTIVE", documents: [d(1, "PAN_CARD_FRONT"), d(2, "AADHAAR_CARD_FRONT")] },
      { applicationId: 1, applicationStatus: "CLOSED", documents: [d(3, "PAN_CARD_FRONT")] },
    ]);
    await screen.findAllByText(/Application #2/);
    const cards = screen.getAllByRole("button", { name: "View document" }).map((b) => b.parentElement!);
    const order = cards.map((c) => `${c.textContent?.match(/^\d+/)?.[0]}|${c.textContent?.match(/Application #\d/)?.[0]}`);
    expect(order.slice(0, 3)).toEqual(["1|Application #2", "2|Application #1", "3|Application #2"]);
  });
});
