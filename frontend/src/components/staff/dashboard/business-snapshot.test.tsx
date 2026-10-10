import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { paiseToINR, type DashKpi, type DashSnapshot } from "@/lib/api/applications";

const kpi = (metric: DashKpi["metric"], count: number): DashKpi => ({
  metric, count, amountPaise: count * 100_000, fresh: count, reloan: 0,
  freshPaise: count * 100_000, reloanPaise: 0, previousCount: null, previousAmountPaise: null,
});
const position = {
  principalPaise: 10_000_000, netDisbursedPaise: 8_820_000, receivablePaise: 12_700_000,
  penaltyPaise: 50_000, receivedPaise: 6_000_000, pendingPaise: 0, waivedPaise: 250_000, overpaidPaise: 30_000, loans: 10,
};
const full = {
  from: "2026-10-01", to: "2026-10-09",
  kpis: { applications: kpi("APPLICATIONS", 5), disbursed: kpi("DISBURSED", 3), pending: kpi("PENDING", 1), rejected: kpi("REJECTED", 1) },
  financial: {
    totalLoan: kpi("DISBURSED", 3), pendingSanctioned: kpi("PENDING", 1), pendingDisbursal: kpi("PENDING", 1),
    averageLoanPaise: 1, averageFreshPaise: 1, averageReloanPaise: 0,
  },
  closed: {
    closedCount: 0, settledCount: 0, partPaidCount: 0, closedPctOfDisbursed: null,
    collectedClosedPaise: 0, collectedSettledPaise: 0, collectedPartPaise: 0, totalCollectedPaise: 0,
  },
  rates: { disbursementRate: null, pendingRate: null, rejectionRate: null },
  pfTable: [], roiTable: [], position,
} as unknown as DashSnapshot;

let data: DashSnapshot;
// Only the "snapshot" query is fed; the sibling monthly/revenue sections stay in their loading state.
vi.mock("./use-dash-query", () => ({
  useDashQuery: (key: string) =>
    key === "snapshot"
      ? { data, isLoading: false, isError: false, error: null, refetch: vi.fn() }
      : { data: undefined, isLoading: true, isError: false, error: null, refetch: vi.fn() },
}));

import { BusinessSnapshot } from "./tabs/business-snapshot";

beforeEach(() => {
  window.matchMedia = ((q: string) => ({
    matches: true, media: q, onchange: null, addListener() {}, removeListener() {},
    addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
});

const wrap = (ui: React.ReactElement) =>
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{ui}</QueryClientProvider>);
const props = (open = vi.fn()) => ({ params: {} as never, open, realAdmin: true, periodLabel: "" });
const esc = (s: string) => s.replace(/[₹.,]/g, "\\$&");

describe("BusinessSnapshot position section", () => {
  it("renders six figures; each card opens its POSITION_* metric", async () => {
    data = full;
    const open = vi.fn();
    wrap(<BusinessSnapshot {...props(open)} />);
    const cards = [
      ["Principal disbursed", position.principalPaise, "POSITION_PRINCIPAL"],
      ["Net disbursed", position.netDisbursedPaise, "POSITION_NET"],
      ["Net receivable", position.receivablePaise, "POSITION_RECEIVABLE"],
      ["Total penalty", position.penaltyPaise, "POSITION_PENALTY"],
      ["Net received", position.receivedPaise, "POSITION_RECEIVED"],
      ["Pending", position.pendingPaise, "POSITION_PENDING"],
    ] as const;
    for (const [title, paise, metric] of cards) {
      await userEvent.click(screen.getByRole("button", { name: new RegExp(`^${title}: ${esc(paiseToINR(paise))} `) }));
      expect(open).toHaveBeenLastCalledWith(expect.objectContaining({ metric }));
    }
  });

  it("explains settlement waivers in the Pending popover", async () => {
    data = full;
    wrap(<BusinessSnapshot {...props()} />);
    await userEvent.hover(screen.getByRole("button", { name: /^Pending: / }));
    const tip = screen.getByRole("tooltip");
    expect(tip).toHaveTextContent(`Waived via settlements: ${paiseToINR(position.waivedPaise)}`);
    expect(tip).toHaveTextContent(`Overpaid by borrowers: ${paiseToINR(position.overpaidPaise)}`);
    expect(tip).toHaveTextContent("Receivable + penalty − received − waived + overpaid");
  });

  it("survives a snapshot with no position (older backend)", () => {
    data = { ...full, position: undefined } as unknown as DashSnapshot;
    wrap(<BusinessSnapshot {...props()} />);
    expect(screen.queryByText("Business position")).not.toBeInTheDocument();
    expect(screen.getByText("Key Performance Indicators")).toBeInTheDocument();
  });
});
