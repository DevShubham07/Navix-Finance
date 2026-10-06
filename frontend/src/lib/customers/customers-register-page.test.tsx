/**
 * Page-level checks for `/staff/customers` (Phase 2):
 *  - "Open" and ⓘ go straight to the row's latest application, and fall back to the customer only
 *    when the row has no application;
 *  - the register says how it is ordered;
 *  - a filter change on page > 1 never asks the server for the stale page under the new filter —
 *    neither for a date-period change (a handler on this page) nor for a segment change that
 *    arrives through the URL (the sidebar's segment links, back/forward).
 *
 * Lives under `src/lib/customers` beside the helpers it exercises; the page itself is imported by path.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  customersApi,
  type CustomerListFilters,
  type CustomerSummary,
  type CustomerSummaryCounts,
} from "@/lib/api/applications";
// `vi.mock` calls below are hoisted above every import, so the page sees the mocked modules.
import CustomersPage from "@/app/staff/customers/page";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/customers",
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

// The real dialogs fetch whole files; here they only have to prove what they were opened on.
vi.mock("@/components/staff/application-detail-dialog", () => ({
  ApplicationDetailDialog: ({ applicationId }: { applicationId: number | null }) =>
    applicationId == null ? null : <div role="dialog">Application {applicationId}</div>,
}));
vi.mock("@/components/staff/customer-detail-dialog", () => ({
  CustomerDetailDialog: ({ customerId }: { customerId: number | null }) =>
    customerId == null ? null : <div role="dialog">Customer {customerId}</div>,
}));
vi.mock("@/components/staff/application-info-dialog", () => ({
  ApplicationInfoDialog: ({ applicationId, customerId }: { applicationId?: number | null; customerId?: number | null }) =>
    applicationId == null && customerId == null ? null : (
      <div role="dialog">
        Summary application={applicationId ?? "none"} customer={customerId ?? "none"}
      </div>
    ),
}));

function row(overrides: Partial<CustomerSummary> = {}): CustomerSummary {
  return {
    customerId: 42,
    name: "Asha Verma",
    pan: "ABCDE1234F",
    mobile: "9876543210",
    applicationCount: 1,
    loanCount: 0,
    latestStatus: "CREDIT_EXEC_PENDING",
    totalOutstandingPaise: 0,
    latestApplicationId: 318,
    createdAt: "2026-10-01T05:30:00Z",
    statusChangedAt: "2026-10-05T05:30:00Z",
    ...overrides,
  };
}

const ROWS = [
  row(),
  // A customer with a loan but no application row on file: nothing to open but the customer view.
  row({
    customerId: 77,
    name: "Bilal Khan",
    pan: "PQRSX9876Z",
    applicationCount: 0,
    loanCount: 1,
    latestStatus: null,
    latestApplicationId: null,
  }),
];

const COUNTS: CustomerSummaryCounts = {
  all: 60,
  incomplete: 0,
  pending: 60,
  review: 0,
  approved: 0,
  disbursementPending: 0,
  active: 0,
  overdue: 0,
  hold: 0,
  rejected: 0,
  closed: 0,
  unallocated: 0,
};

function tree(client: QueryClient) {
  return (
    <QueryClientProvider client={client}>
      <CustomersPage />
    </QueryClientProvider>
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const utils = render(tree(client));
  return { ...utils, rerenderPage: () => utils.rerender(tree(client)) };
}

let pageCalls: CustomerListFilters[];

beforeEach(() => {
  replace.mockReset();
  window.history.replaceState({}, "", "/staff/customers");
  pageCalls = [];
  // `useStaffMe` asks the BFF who is signed in.
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ session: { id: 5, name: "Head", role: "CREDIT_HEAD" } })),
  );
  vi.spyOn(customersApi, "page").mockImplementation(async (filters: CustomerListFilters = {}) => {
    pageCalls.push(filters);
    return { rows: ROWS, page: filters.page ?? 1, size: filters.size ?? 25, total: 60 };
  });
  vi.spyOn(customersApi, "summary").mockResolvedValue(COUNTS);
});

afterEach(() => {
  vi.restoreAllMocks();
});

function rowFor(name: string): HTMLElement {
  return screen.getByText(name).closest("tr") as HTMLElement;
}

async function goToPage2() {
  fireEvent.click(await screen.findByRole("button", { name: /Next/ }));
  expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
  await waitFor(() => expect(pageCalls.some((f) => f.page === 2)).toBe(true));
}

describe("CustomersPage", () => {
  it("opens the row's latest application directly, without the customer roll-up", async () => {
    const get = vi.spyOn(customersApi, "get");
    renderPage();
    await screen.findByText("Asha Verma");

    fireEvent.click(within(rowFor("Asha Verma")).getByRole("button", { name: "Open" }));
    expect(within(screen.getByRole("dialog")).getByText("Application 318")).toBeInTheDocument();
    expect(screen.queryByText(/^Customer \d+$/)).not.toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });

  it("falls back to the customer view for a row with no application", async () => {
    renderPage();
    await screen.findByText("Bilal Khan");

    fireEvent.click(within(rowFor("Bilal Khan")).getByRole("button", { name: "Open" }));
    expect(within(screen.getByRole("dialog")).getByText("Customer 77")).toBeInTheDocument();
  });

  it("opens the quick summary on the application id when the row has one, else on the customer", async () => {
    renderPage();
    await screen.findByText("Asha Verma");

    fireEvent.click(within(rowFor("Asha Verma")).getByRole("button", { name: "Quick summary" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("Summary application=318 customer=none");
  });

  it("opens the quick summary on the customer when the row has no application", async () => {
    renderPage();
    await screen.findByText("Bilal Khan");

    fireEvent.click(within(rowFor("Bilal Khan")).getByRole("button", { name: "Quick summary" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("Summary application=none customer=77");
  });

  it("says how the register is ordered, visibly and to a screen reader", async () => {
    renderPage();
    await screen.findByText("Asha Verma");

    expect(screen.getByText(/Sorted by stage date/)).toBeVisible();
    expect(screen.getByRole("table", { name: /Customers, grouped by stage date, newest first/ })).toBeInTheDocument();
    for (const th of screen.getAllByRole("columnheader")) expect(th).toHaveAttribute("scope", "col");
  });

  it("restarts at page 1 on a period change without requesting the stale page", async () => {
    renderPage();
    await goToPage2();

    fireEvent.click(screen.getByRole("button", { name: "Today" }));
    await waitFor(() => expect(pageCalls.some((f) => f.from != null && f.page === 1)).toBe(true));
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    expect(pageCalls.filter((f) => f.from != null && f.page !== 1)).toEqual([]);
  });

  it("keeps the page when a period change leaves the date window as it was", async () => {
    renderPage();
    await goToPage2();

    // All time → Custom with nothing applied yet: still no window, so still the same rows.
    fireEvent.click(screen.getByRole("button", { name: "Custom" }));
    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
  });

  it("restarts at page 1 when the segment arrives through the URL, without requesting the stale page", async () => {
    const { rerenderPage } = renderPage();
    await goToPage2();

    // What the sidebar's segment link (or back/forward) does: the URL changes under a mounted page.
    window.history.replaceState({}, "", "/staff/customers?seg=overdue");
    rerenderPage();

    await waitFor(() => expect(pageCalls.some((f) => f.seg === "overdue" && f.page === 1)).toBe(true));
    expect(await screen.findByText("Page 1 of 3")).toBeInTheDocument();
    expect(pageCalls.filter((f) => f.seg === "overdue" && f.page !== 1)).toEqual([]);
  });
});
