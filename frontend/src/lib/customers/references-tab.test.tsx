import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { staffApi, type ApplicationView, type CustomerDetail } from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

let role = "ADMIN";
vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

function renderTab(empty = false) {
  vi.spyOn(staffApi, "references").mockResolvedValue(empty ? [] : [
    { slot: 1, fullName: "Asha Rao", mobile: "9876543210", relation: "SPOUSE" },
    { slot: 2, fullName: "Ravi Rao", mobile: "9123456780", relation: "FRIEND" },
  ]);
  const app = { id: 318, status: "ACTIVE" } as unknown as ApplicationView;
  const detail = { customerId: 42, profile: null, applications: [app], loans: [], payments: [] } as CustomerDetail;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab="references" detail={detail} customerId={42} app={app} applicationId={318} />
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

describe("References tab", () => {
  it("renders a card per reference with a wa.me link, and Add for ADMIN", async () => {
    renderTab();
    expect(await screen.findByText("Reference 1")).toBeInTheDocument();
    expect(screen.getByText("Reference 2")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /WhatsApp Asha Rao/ })).toHaveAttribute("href", "https://wa.me/919876543210");
    expect(screen.queryByRole("button", { name: /Add reference/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Delete reference/)).not.toBeInTheDocument();
  });

  it("offers Add with no references and opens two blank rows", async () => {
    renderTab(true);
    fireEvent.click(await screen.findByRole("button", { name: /Add reference/ }));
    expect(screen.getAllByText("Full name")).toHaveLength(2);
  });

  it("hides Add / edit / delete for other roles", async () => {
    role = "CREDIT_EXECUTIVE";
    renderTab();
    expect(await screen.findByText("Reference 1")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Add reference/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Delete reference/)).not.toBeInTheDocument();
  });
});
