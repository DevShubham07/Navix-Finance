/**
 * The one pop-up's entry contract: the entry point picks the tab (`initialTab` beats the
 * stage-aware default), a loan-only entry resolves its application, and the panel has a fixed size.
 */
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { staffApi, customersApi, type ApplicationView, type CustomerDetail } from "@/lib/api/applications";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";

vi.mock("@/components/staff/customer-tabs", () => ({
  CUSTOMER_TABS: [
    { key: "customer", label: "Customer" },
    { key: "loan", label: "Loan" },
    { key: "repayment", label: "Repayment" },
    { key: "sanction", label: "Sanction" },
  ],
  CustomerTabBody: ({ tab }: { tab: string }) => <div>body-tab:{tab}</div>,
}));
vi.mock("@/components/staff/pipeline/hooks", () => ({
  useStaffMe: () => ({ data: { role: "ADMIN" } }),
  useCan: () => () => true,
  REVIEW_PERMS: ["customer:view"],
}));
vi.mock("@/components/staff/live-pipeline", () => ({ NoAccessNotice: () => null }));
vi.mock("@/components/staff/detail-parts", () => ({ NeedsManualReviewBadge: () => null }));

const app = { id: 318, customerId: 42, status: "ACTIVE", loanId: 208 } as ApplicationView;

function renderDialog(props: Partial<React.ComponentProps<typeof ApplicationDetailDialog>>) {
  vi.spyOn(staffApi, "get").mockResolvedValue(app);
  vi.spyOn(staffApi, "creditBrief").mockResolvedValue({ available: false } as never);
  vi.spyOn(staffApi, "getProfile").mockResolvedValue({} as never);
  vi.spyOn(customersApi, "get").mockResolvedValue({
    customerId: 42,
    applications: [{ id: 999, loanId: null }, app],
    loans: [],
  } as unknown as CustomerDetail);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <ApplicationDetailDialog applicationId={null} onClose={() => {}} {...props} />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.restoreAllMocks());

describe("ApplicationDetailDialog entry", () => {
  it("lands on the stage default when no initialTab is given", async () => {
    renderDialog({ applicationId: 318 });
    expect(await screen.findByText("body-tab:repayment")).toBeInTheDocument();
  });

  it("initialTab wins over the stage default", async () => {
    renderDialog({ applicationId: 318, initialTab: "loan" });
    expect(await screen.findByText("body-tab:loan")).toBeInTheDocument();
    expect(screen.queryByText("body-tab:repayment")).not.toBeInTheDocument();
  });

  it("resolves the application from a loan id alone", async () => {
    vi.spyOn(staffApi, "loan").mockResolvedValue({ id: 208, customerId: 42 } as never);
    renderDialog({ loanId: 208, initialTab: "repayment" });
    expect(await screen.findByText("body-tab:repayment")).toBeInTheDocument();
    await waitFor(() => expect(staffApi.get).toHaveBeenCalledWith(318));
  });

  it("shows an error, inside the same frame, when no application carries the loan", async () => {
    vi.spyOn(staffApi, "loan").mockResolvedValue({ id: 5, customerId: 42 } as never);
    renderDialog({ loanId: 5 });
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toHaveClass("!h-[88dvh]");
  });

  it("has a fixed-height panel while loading and once loaded", async () => {
    renderDialog({ applicationId: 318 });
    expect(screen.getByRole("dialog")).toHaveClass("!h-[88dvh]", "!w-[92vw]");
    await screen.findByText("body-tab:repayment");
    expect(screen.getByRole("dialog")).toHaveClass("!h-[88dvh]");
  });
});
