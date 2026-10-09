import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { StaffRole } from "@/lib/auth/rbac";

let session: { id: string; name: string; role: StaffRole; realRole: StaffRole } | null = null;
let search = "";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/staff/dashboard",
  useSearchParams: () => new URLSearchParams(search),
}));
vi.mock("@/lib/auth/staff-session", () => ({ useStaffSession: () => ({ session, loading: false }) }));
vi.mock("@/components/staff/dashboard/queue-strip", () => ({ QueueStrip: () => <div data-testid="queue-strip" /> }));
vi.mock("@/components/staff/live-pipeline", () => ({
  NoAccessNotice: ({ message }: { message: string }) => <div data-testid="no-access">{message}</div>,
}));
vi.mock("@/components/staff/application-detail-dialog", () => ({ ApplicationDetailDialog: () => null }));

// Every analytics call stays pending: the shell, tab bar and loading states are what is under test.
const calls = vi.fn((..._args: unknown[]) => new Promise(() => {}));
vi.mock("@/lib/api/applications", async (orig) => {
  const actual = await orig<typeof import("@/lib/api/applications")>();
  const dashboardApi = Object.fromEntries(Object.keys(actual.dashboardApi).map((k) => [k, (...a: unknown[]) => calls(k, ...a)]));
  return { ...actual, dashboardApi };
});

import StaffDashboardPage from "@/app/staff/dashboard/page";

const TABS = [
  "Business Snapshot",
  "Team & Performance",
  "Calendar",
  "Map",
  "Company-wise",
  "Credit Executive Report",
  "Sales Ops Report",
  "Collection Allocation",
];

const renderPage = () =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffDashboardPage />
    </QueryClientProvider>,
  );

describe("staff dashboard page", () => {
  beforeEach(() => {
    search = "";
    calls.mockClear();
  });

  it("real ADMIN lands on the Admin overview with all 8 tabs and a welcome", async () => {
    session = { id: "1", name: "Nitin Parekh", role: "ADMIN", realRole: "ADMIN" };
    renderPage();
    await waitFor(() => expect(screen.getByText("Welcome, Nitin")).toBeInTheDocument());
    const tabs = screen.getAllByRole("tab").map((t) => t.textContent);
    expect(tabs).toEqual(TABS);
    expect(screen.getByRole("tab", { name: "Business Snapshot" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("queue-strip")).toBeInTheDocument();
    // the Business Snapshot asked for its data under the ADMIN view
    await waitFor(() => expect(calls).toHaveBeenCalledWith("snapshot", expect.objectContaining({ view: "ADMIN" })));
  });

  it("CREDIT_EXECUTIVE sees the credit view and no tab bar", async () => {
    session = { id: "7", name: "Asha Rao", role: "CREDIT_EXECUTIVE", realRole: "CREDIT_EXECUTIVE" };
    renderPage();
    await waitFor(() => expect(screen.getByText("Welcome, Asha")).toBeInTheDocument());
    expect(screen.queryAllByRole("tab")).toHaveLength(0);
    expect(screen.getByText("Lead Overview")).toBeInTheDocument();
    await waitFor(() =>
      expect(calls).toHaveBeenCalledWith("roleView", expect.objectContaining({ view: "CREDIT_EXECUTIVE" })),
    );
    expect(calls).not.toHaveBeenCalledWith("snapshot", expect.anything());
  });

  it("DSA gets the no-access notice and no analytics call", async () => {
    session = { id: "9", name: "Dee Esa", role: "DSA", realRole: "DSA" };
    renderPage();
    await waitFor(() => expect(screen.getByTestId("no-access")).toBeInTheDocument());
    expect(calls).not.toHaveBeenCalled();
  });
});
