/**
 * Export must hand over exactly what the table is showing.
 *
 * Two registers were handing `ExportMenu` their raw query result while the table rendered a narrowed
 * set, so a filtered export quietly returned the whole book: the settlements register ignored its
 * status chips, and the staff roster ignored both its status tab and its search box. These tests
 * stand on the seam — `ExportMenu` is stubbed to print what it was given — so they fail on the data
 * handed to the exporter rather than on anything about CSV or PDF.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import type { SettlementView } from "@/lib/api/applications";

const exported = vi.fn();

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/staff",
}));

vi.mock("@/components/staff/export-menu", () => ({
  ExportMenu: ({ rows }: { rows: Array<{ id?: string; email?: string }> }) => {
    exported(rows);
    return <div data-testid="export-rows">{rows.length}</div>;
  },
}));

vi.mock("@/components/staff/pipeline/hooks", async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  useStaffMe: () => ({ data: { id: "1", name: "Admin", role: "ADMIN", realRole: "ADMIN" } }),
  useCan: () => () => true,
}));

vi.mock("@/lib/api/applications", async (orig) => {
  const actual = await orig<Record<string, unknown>>();
  return {
    ...actual,
    collectionsApi: { listSettlements: vi.fn() },
    adminApi: { listStaff: vi.fn(), updateStaff: vi.fn() },
  };
});

const { collectionsApi, adminApi } = await import("@/lib/api/applications");
const SettlementsPage = (await import("@/app/staff/collections/settlements/page")).default;
const StaffAdminPage = (await import("@/app/staff/admin/staff/page")).default;

function wrap(ui: React.ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

const settlement = (id: string, status: SettlementView["status"]): SettlementView =>
  ({
    id,
    collectionCaseId: `case-${id}`,
    settlementAmountPaise: 100000,
    status,
    proposedBy: 9,
    proposedByName: "Officer",
    createdAt: "2026-01-01T00:00:00Z",
  }) as SettlementView;

/** The rows handed to the exporter on the most recent render. */
const lastExported = () => exported.mock.calls.at(-1)?.[0] as Array<Record<string, unknown>>;

beforeEach(() => {
  exported.mockClear();
});

describe("settlements register", () => {
  it("exports the segment the chips have narrowed to, not every settlement", async () => {
    vi.mocked(collectionsApi.listSettlements).mockResolvedValue([
      settlement("a", "PROPOSED"),
      settlement("b", "APPROVED"),
      settlement("c", "REJECTED"),
      settlement("d", "REJECTED"),
    ]);

    wrap(<SettlementsPage />);
    await waitFor(() => expect(screen.getByTestId("export-rows")).toHaveTextContent("4"));

    fireEvent.click(screen.getByRole("button", { name: /Rejected/ }));

    await waitFor(() => expect(screen.getByTestId("export-rows")).toHaveTextContent("2"));
    expect(lastExported().map((r) => r.id)).toEqual(["c", "d"]);
  });

  it("exports what a column filter has narrowed to, across every page", async () => {
    vi.mocked(collectionsApi.listSettlements).mockResolvedValue([
      settlement("a", "PROPOSED"),
      settlement("b", "APPROVED"),
      settlement("c", "REJECTED"),
    ]);

    wrap(<SettlementsPage />);
    await waitFor(() => expect(screen.getByTestId("export-rows")).toHaveTextContent("3"));

    // The Status column's own filter, not the chip above the table.
    fireEvent.click(screen.getByRole("button", { name: /Filter Status/ }));
    // The checklist reads the label; what travels is the stored enum.
    const pnl = await screen.findByRole("dialog", { name: /Filter and sort Status/ });
    // Excel semantics: everything starts ticked, so clear the lot and tick back the one wanted.
    fireEvent.click(within(pnl).getByLabelText("(Select All)"));
    fireEvent.click(within(pnl).getByLabelText("Approved"));
    fireEvent.click(within(pnl).getByRole("button", { name: "Apply" }));

    await waitFor(() => expect(screen.getByTestId("export-rows")).toHaveTextContent("1"));
    expect(lastExported().map((r) => r.id)).toEqual(["b"]);
  });
});

describe("staff roster", () => {
  const person = (id: number, name: string, status: string) => ({
    id,
    name,
    email: `${name.toLowerCase()}@navix.example`,
    role: "CREDIT_EXECUTIVE",
    status,
  });

  it("exports the active tab, not the whole roster", async () => {
    vi.mocked(adminApi.listStaff).mockResolvedValue([
      person(1, "Asha", "ACTIVE"),
      person(2, "Bela", "ACTIVE"),
      person(3, "Chen", "DISABLED"),
    ] as never);

    wrap(<StaffAdminPage />);
    await waitFor(() => expect(screen.getByTestId("export-rows")).toHaveTextContent("2"));

    fireEvent.click(screen.getByRole("tab", { name: /Disabled/ }));

    await waitFor(() => expect(screen.getByTestId("export-rows")).toHaveTextContent("1"));
    expect(lastExported().map((r) => r.name)).toEqual(["Chen"]);
  });

  it("exports what the search box has narrowed to", async () => {
    vi.mocked(adminApi.listStaff).mockResolvedValue([
      person(1, "Asha", "ACTIVE"),
      person(2, "Bela", "ACTIVE"),
    ] as never);

    wrap(<StaffAdminPage />);
    await waitFor(() => expect(screen.getByTestId("export-rows")).toHaveTextContent("2"));

    // SearchBar only commits on submit, never per keystroke.
    fireEvent.change(screen.getByLabelText("Name or email"), { target: { value: "asha" } });
    fireEvent.submit(screen.getByRole("search"));

    await waitFor(() => expect(screen.getByTestId("export-rows")).toHaveTextContent("1"));
    expect(lastExported().map((r) => r.name)).toEqual(["Asha"]);
  });
});
