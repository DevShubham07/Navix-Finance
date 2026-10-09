/**
 * Page-level checks for `/staff/telecalling` (Phase 2): "Select all" scoped to the visible page,
 * per-row in-flight state for concurrent clicks, the inline "Assign to me" error, and the count of
 * rows other staff own.
 *
 * Lives under `src/lib/telecalling` beside the helpers it exercises; the page is imported by path.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { customersApi, staffApi, type CustomerDetail, type TelecallingView } from "@/lib/api/applications";
// `vi.mock` calls below are hoisted above every import, so the page sees the mocked modules.
import TelecallingPage from "@/app/staff/telecalling/page";

// Neither is under test here; both fetch on their own.
vi.mock("@/components/staff/application-detail-dialog", () => ({ ApplicationDetailDialog: () => null }));
vi.mock("@/components/staff/customer-owner-picker", () => ({ CustomerOwnerPicker: () => null }));

const ME = 5;

function row(id: number, overrides: Partial<TelecallingView> = {}): TelecallingView {
  return {
    id,
    customerId: 1000 + id,
    status: "DRAFT",
    customerName: `Customer ${id}`,
    mobile: "9876543210",
    email: `c${id}@example.com`,
    pan: null,
    stepsCompleted: 3,
    stepsRequired: 5,
    ownerStaffId: null,
    staleDays: 1,
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TelecallingPage />
    </QueryClientProvider>,
  );
}

function section(title: string): HTMLElement {
  return screen.getByRole("heading", { name: title }).closest("section")!;
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ session: { id: ME, name: "Tara", role: "TELECALLER" } })),
  );
});

describe("select all", () => {
  it("ticks the visible page only, not every row in the section", async () => {
    vi.spyOn(staffApi, "telecalling").mockResolvedValue(Array.from({ length: 30 }, (_, i) => row(i + 1)));
    renderPage();
    await screen.findByText("Customer 1");
    const unallocated = section("Unallocated");

    fireEvent.click(within(unallocated).getByRole("checkbox", { name: "Select all on this page" }));
    expect(within(unallocated).getByRole("button", { name: "Send to selected (25)" })).toBeInTheDocument();

    fireEvent.click(within(unallocated).getByRole("checkbox", { name: "Select all on this page" }));
    expect(within(unallocated).queryByRole("button", { name: /Send to selected/ })).not.toBeInTheDocument();
  });
});

describe("assign to me", () => {
  it("keeps every clicked row busy until its own request settles", async () => {
    vi.spyOn(staffApi, "telecalling").mockResolvedValue([row(1), row(2)]);
    const resolvers: Array<() => void> = [];
    vi.spyOn(customersApi, "assignOwner").mockImplementation(
      () => new Promise<CustomerDetail>((resolve) => resolvers.push(() => resolve({} as CustomerDetail))),
    );
    renderPage();
    await screen.findByText("Customer 1");
    const [first, second] = within(section("Unallocated")).getAllByRole("button", { name: "Assign to me" });

    fireEvent.click(first);
    fireEvent.click(second);
    await waitFor(() => expect(first).toBeDisabled());
    expect(second).toBeDisabled();

    resolvers[1]();
    await waitFor(() => expect(second).toBeEnabled());
    // The first call is still in flight; its button must not have been re-enabled by the second.
    expect(first).toBeDisabled();
    resolvers[0]();
    await waitFor(() => expect(first).toBeEnabled());
  });

  it("shows a failed assignment next to that row's button", async () => {
    vi.spyOn(staffApi, "telecalling").mockResolvedValue([row(1), row(2)]);
    vi.spyOn(customersApi, "assignOwner").mockRejectedValue(new Error("Already owned"));
    renderPage();
    await screen.findByText("Customer 1");

    const rowOne = screen.getByText("Customer 1").closest("tr")!;
    fireEvent.click(within(rowOne).getByRole("button", { name: "Assign to me" }));

    expect(await within(rowOne).findByRole("alert")).toHaveTextContent("Already owned");
    const rowTwo = screen.getByText("Customer 2").closest("tr")!;
    expect(within(rowTwo).queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("rows owned by other staff", () => {
  it("counts them under each section without listing them", async () => {
    vi.spyOn(staffApi, "telecalling").mockResolvedValue([
      row(1),
      row(2, { ownerStaffId: ME }),
      row(3, { ownerStaffId: 9, customerName: "Hidden One" }),
      row(4, { ownerStaffId: 9, customerName: "Hidden Two" }),
    ]);
    renderPage();
    await screen.findByText("Customer 1");

    for (const title of ["Unallocated", "My customers"]) {
      expect(within(section(title)).getByText("2 applications assigned to other staff aren't shown.")).toBeInTheDocument();
    }
    expect(screen.queryByText("Hidden One")).not.toBeInTheDocument();
    expect(screen.queryByText("Hidden Two")).not.toBeInTheDocument();
  });
});

describe("cells", () => {
  it("draws completeness as a bar beside the fraction, and marks a missing email", async () => {
    vi.spyOn(staffApi, "telecalling").mockResolvedValue([row(1, { email: null })]);
    renderPage();
    await screen.findByText("Customer 1");
    const tr = screen.getByText("Customer 1").closest("tr")!;

    expect(within(tr).getByText("3/5")).toBeInTheDocument();
    expect(tr.querySelector('[style*="width: 60%"]')).not.toBeNull();
    expect(within(tr).getByText("No email on file")).toBeInTheDocument();
    expect(within(tr).queryByText("no email")).not.toBeInTheDocument();
  });
});
