/**
 * Regression: a column filter on a bulk-select queue must narrow "Select all" to the rows it is
 * SHOWING. Before the register sat above the selection, the header checkbox ticked every id in the
 * queue — a bulk reject (30-day borrower cooling-off) would have hit applications the filter hid.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { staffApi, type ApplicationView } from "@/lib/api/applications";
import { StatusQueue } from "@/components/staff/pipeline/status-queue";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/staff/applications",
  useSearchParams: () => new URLSearchParams(""),
}));

// Disbursement Head holds loan:disburse, so the queue offers bulk reject (and the checkbox column).
const ME = { id: "10", name: "Dee Head", role: "DISBURSEMENT_HEAD" };

const app = (id: number, name: string, by: string) =>
  ({ id, customerId: id, status: "DISBURSEMENT_PENDING", customerName: name, creditDecidedByName: by }) as ApplicationView;

const rowBoxes = () =>
  within(screen.getByRole("table")).getAllByRole("checkbox").filter((c) => c.getAttribute("aria-label") !== "Select all");

beforeEach(() => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session: ME })));
  vi.spyOn(staffApi, "listByStatus").mockResolvedValue([
    app(1, "Asha", "Ravi"),
    app(2, "Bela", "Ravi"),
    app(3, "Chen", "Meena"),
  ]);
});
afterEach(() => vi.restoreAllMocks());

describe("StatusQueue bulk selection under a column filter", () => {
  it("Select all ticks only the filtered rows, never the ones the filter hides", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <StatusQueue
          title="Standard disbursement"
          registerId="queue-test"
          status="DISBURSEMENT_PENDING"
          bulk={{ rejectMode: "disbursement" }}
        />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(rowBoxes()).toHaveLength(3));

    fireEvent.click(screen.getByRole("button", { name: /^Filter Credit exec/ }));
    const panel = screen.getByRole("dialog", { name: /Filter and sort Credit exec/ });
    fireEvent.click(within(panel).getByLabelText("(Select All)"));
    fireEvent.click(within(panel).getByLabelText("Meena"));
    fireEvent.click(within(panel).getByRole("button", { name: "Apply" }));
    await waitFor(() => expect(rowBoxes()).toHaveLength(1));

    fireEvent.click(screen.getByLabelText("Select all"));
    expect(screen.getByText("1 selected")).toBeInTheDocument();
    expect(rowBoxes().every((c) => (c as HTMLInputElement).checked)).toBe(true);
  });
});
