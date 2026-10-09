import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DashRecordRow, DashRecords } from "@/lib/api/applications";

const records = vi.fn();
vi.mock("@/lib/api/applications", async (orig) => {
  const actual = await orig<typeof import("@/lib/api/applications")>();
  return { ...actual, dashboardApi: { ...actual.dashboardApi, records: (p: unknown) => records(p) } };
});
vi.mock("@/lib/auth/staff-session", () => ({ useStaffSession: () => ({ session: null, loading: false }) }));
vi.mock("@/components/staff/application-detail-dialog", () => ({
  ApplicationDetailDialog: ({ applicationId }: { applicationId: number | null }) => (
    <div data-testid="detail">detail {applicationId}</div>
  ),
}));

import { RecordsDrawer } from "./records-drawer";

const row = (id: number, segment: "FRESH" | "RELOAN"): DashRecordRow => ({
  applicationId: id,
  loanId: id + 100,
  customerId: id + 1000,
  customerName: `Customer ${id}`,
  mobileLast4: "1234",
  status: "ACTIVE",
  segment,
  amountPaise: 1_000_000,
  owedPaise: null,
  disbursedOn: "2026-10-01",
  dueDate: "2026-10-30",
  closedOn: null,
  assigneeName: "Asha",
  state: "Haryana",
});

function respond(p: { segment?: string }): DashRecords {
  const all = [row(1, "FRESH"), row(2, "FRESH"), row(3, "RELOAN")];
  const rows =
    p.segment === "FRESH"
      ? all.filter((r) => r.segment === "FRESH")
      : p.segment === "RELOAN"
        ? all.filter((r) => r.segment === "RELOAN")
        : all;
  return { rows, total: rows.length, freshCount: 2, reloanCount: 1, sumPaise: rows.length * 1_000_000 };
}

const params = { view: "ADMIN" as const, from: "2026-10-01", to: "2026-10-09" };
const wrap = (ui: React.ReactElement) =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      {ui}
    </QueryClientProvider>,
  );

describe("RecordsDrawer", () => {
  beforeEach(() => {
    records.mockReset();
    records.mockImplementation(async (p: { segment?: string }) => respond(p));
  });

  it("total line matches the response (count and sum)", async () => {
    wrap(<RecordsDrawer target={{ metric: "DISBURSED", title: "Disbursed cases" }} onClose={() => {}} params={params} />);
    await waitFor(() => expect(screen.getByTestId("records-total")).toHaveTextContent("3 records · ₹30,000"));
    expect(screen.getByText("Customer 1")).toBeInTheDocument();
    expect(records).toHaveBeenCalledWith(
      expect.objectContaining({ metric: "DISBURSED", segment: "ALL", view: "ADMIN", page: 0 }),
    );
  });

  it("segment pills show counts and refetch with the chosen segment", async () => {
    wrap(<RecordsDrawer target={{ metric: "DISBURSED", title: "Disbursed cases" }} onClose={() => {}} params={params} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "All 3" })).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Fresh 2" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Re-loan 1" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Re-loan 1" }));
    await waitFor(() => expect(records).toHaveBeenCalledWith(expect.objectContaining({ segment: "RELOAN" })));
    await waitFor(() => expect(screen.getByTestId("records-total")).toHaveTextContent("1 record · ₹10,000"));
    expect(screen.queryByText("Customer 1")).not.toBeInTheDocument();
    expect(screen.getByText("Customer 3")).toBeInTheDocument();
  });

  it("opens on the requested segment and uses a pinned range", async () => {
    wrap(
      <RecordsDrawer
        target={{
          metric: "PF_RATE",
          key: "10",
          segment: "FRESH",
          title: "PF 10%",
          range: { from: "2026-09-01", to: "2026-09-30" },
        }}
        onClose={() => {}}
        params={params}
      />,
    );
    await waitFor(() =>
      expect(records).toHaveBeenCalledWith(
        expect.objectContaining({
          metric: "PF_RATE",
          key: "10",
          segment: "FRESH",
          from: "2026-09-01",
          to: "2026-09-30",
        }),
      ),
    );
  });

  it("clicking a row opens the application detail", async () => {
    wrap(<RecordsDrawer target={{ metric: "DISBURSED", title: "Disbursed cases" }} onClose={() => {}} params={params} />);
    fireEvent.click(await screen.findByText("Customer 2"));
    expect(screen.getByTestId("detail")).toHaveTextContent("detail 2");
  });

  it("shows an error state with retry when the call fails", async () => {
    records.mockRejectedValue(new Error("boom"));
    wrap(<RecordsDrawer target={{ metric: "DISBURSED", title: "Disbursed cases" }} onClose={() => {}} params={params} />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
