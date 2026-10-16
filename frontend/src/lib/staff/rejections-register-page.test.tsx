/**
 * `/staff/admin/rejections` as a register: its Reason dropdown became the Reason column's own
 * filter, and "Rejected on" is an IST business day rather than the first ten characters of a UTC
 * timestamp — the two things the migration changed about what an operator sees.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { staffApi, type RejectionView } from "@/lib/api/applications";
import AdminRejectionsPage from "@/app/staff/admin/rejections/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/staff/admin/rejections",
  useSearchParams: () => new URLSearchParams(""),
}));

const ME = { id: "10", name: "Admin Ten", role: "ADMIN" };

function rejection(over: Partial<RejectionView>): RejectionView {
  return {
    id: 1,
    applicationId: 1,
    customerId: 1,
    borrowerName: "Asha",
    mobile: "9000000001",
    pan: null,
    salaryAccountNumber: null,
    salaryIfsc: null,
    amountRequestedPaise: 1_000_000,
    creditScore: 610,
    starRating: null,
    reasonCode: "MANUAL",
    reasonDetail: null,
    auto: false,
    blockedUntil: null,
    createdAt: "2026-03-04T05:00:00Z",
    ...over,
  } as RejectionView;
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AdminRejectionsPage />
    </QueryClientProvider>,
  );
}

const bodyRows = () => within(screen.getByRole("table")).getAllByRole("row").slice(1);

beforeEach(() => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session: ME })));
});

afterEach(() => vi.restoreAllMocks());

describe("AdminRejectionsPage", () => {
  it("narrows on the Reason column and carries that into the export count", async () => {
    vi.spyOn(staffApi, "rejections").mockResolvedValue([
      rejection({ id: 11, borrowerName: "Asha", reasonCode: "MANUAL" }),
      rejection({ id: 12, borrowerName: "Bela", reasonCode: "FRAUD_REJECTED" }),
      rejection({ id: 13, borrowerName: "Chen", reasonCode: "FRAUD_REJECTED" }),
    ]);
    renderPage();
    await waitFor(() => expect(bodyRows()).toHaveLength(3));

    fireEvent.click(screen.getByRole("button", { name: /^Filter Reason/ }));
    const panel = screen.getByRole("dialog", { name: /Filter and sort Reason/ });
    // Everything starts ticked, Excel-style: clear the lot, tick back the one wanted. The checklist
    // reads the label; the filter travels as the stored FRAUD_REJECTED.
    fireEvent.click(within(panel).getByLabelText("(Select All)"));
    fireEvent.click(within(panel).getByLabelText("Fraud rejected"));
    fireEvent.click(within(panel).getByRole("button", { name: "Apply" }));

    await waitFor(() => expect(bodyRows()).toHaveLength(2));
    expect(screen.getByText("2 rejections of 3")).toBeInTheDocument();
  });

  it("dates a late-evening IST rejection to the day it happened there", async () => {
    // 2026-03-04 20:30 UTC is 2026-03-05 02:00 in IST. A `.slice(0, 10)` says the 4th.
    vi.spyOn(staffApi, "rejections").mockResolvedValue([
      rejection({ id: 11, createdAt: "2026-03-04T20:30:00Z" }),
    ]);
    renderPage();
    await waitFor(() => expect(bodyRows()).toHaveLength(1));
    expect(bodyRows()[0]).toHaveTextContent("2026-03-05");
  });
});
