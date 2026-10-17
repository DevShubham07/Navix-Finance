import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { paiseToINR, type DashCalendar } from "@/lib/api/applications";

const month = "2026-09";
const calendar = (mode: string, extra: Partial<DashCalendar> = {}): DashCalendar => ({
  month,
  mode,
  days: [
    { date: `${month}-10`, count: 3, amountPaise: 3_000_000 },
    { date: `${month}-30`, count: 2, amountPaise: 1_500_000 },
  ],
  daysWithData: 2,
  totalCount: 5,
  totalAmountPaise: 4_500_000,
  ...extra,
});

// The hook's `extra` is [month, mode]; each mode gets its own payload so switching the pill re-renders.
const byMode: Record<string, DashCalendar> = {
  DUE: calendar("DUE"),
  DISBURSED: calendar("DISBURSED"),
  ACTIVE: calendar("ACTIVE", {
    days: [
      { date: `${month}-10`, count: 3, amountPaise: null },
      { date: `${month}-30`, count: 2, amountPaise: null },
    ],
    totalCount: 2,
    totalAmountPaise: null,
    snapshot: true,
  }),
  DUE_OPEN: calendar("DUE_OPEN", { totalCount: 4, totalAmountPaise: 2_100_000 }),
};
vi.mock("./use-dash-query", () => ({
  useDashQuery: (_key: string, _params: unknown, extra: readonly unknown[]) => ({
    data: byMode[String(extra[1])],
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
}));
vi.mock("./fmt", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./fmt")>()),
  todayIso: () => `${month}-15`,
}));

import { CalendarTab, heatStep } from "./tabs/calendar-tab";

beforeEach(() => {
  window.matchMedia = ((q: string) => ({
    matches: true, media: q, onchange: null, addListener() {}, removeListener() {},
    addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
});

const props = (open = vi.fn()) => ({ params: { view: "ADMIN" } as never, open, realAdmin: true, periodLabel: "" });
const chip = (label: string) => screen.getByText(label).parentElement as HTMLElement;

describe("CalendarTab", () => {
  it("offers Active loans beside the three existing modes", () => {
    render(<CalendarTab {...props()} />);
    const group = screen.getByRole("group", { name: "Calendar mode" });
    expect(group).toHaveTextContent("Due Amount");
    expect(group).toHaveTextContent("Disbursed Amount");
    expect(group).toHaveTextContent("Customer Birthday");
    expect(group).toHaveTextContent("Active loans");
  });

  it("labels summed modes as totals", () => {
    render(<CalendarTab {...props()} />);
    expect(chip("Total cases")).toHaveTextContent("5");
    expect(chip("Total amount")).toHaveTextContent(paiseToINR(4_500_000));
  });

  it("shows Active as a count of open cases only, and drills into CALENDAR_ACTIVE", async () => {
    const open = vi.fn();
    render(<CalendarTab {...props(open)} />);
    await userEvent.click(screen.getByRole("button", { name: "Active loans" }));
    expect(chip("Open at month end")).toHaveTextContent("2");
    expect(screen.queryByText("Total cases")).toBeNull();
    expect(screen.queryByText("Total amount")).toBeNull();

    const cell = screen.getByRole("gridcell", { name: /: 2 open loans$/ });
    expect(cell).toHaveTextContent("2");
    await userEvent.click(cell);
    expect(open).toHaveBeenCalledWith(
      expect.objectContaining({ metric: "CALENDAR_ACTIVE", key: `${month}-30`, range: { from: `${month}-30`, to: `${month}-30` } }),
    );
  });
});

describe("CalendarTab · Due (open)", () => {
  it("is its own pill, totalling what the open loans still owe", async () => {
    render(<CalendarTab {...props()} />);
    expect(screen.getByRole("group", { name: "Calendar mode" })).toHaveTextContent("Due (open)");
    await userEvent.click(screen.getByRole("button", { name: "Due (open)" }));
    expect(chip("Total cases")).toHaveTextContent("4");
    expect(chip("Still owed")).toHaveTextContent(paiseToINR(2_100_000));
  });
});

describe("heatStep", () => {
  it("is 0 with no data and 1..5 against the busiest day", () => {
    expect(heatStep(0, 10)).toBe(0);
    expect(heatStep(1, 10)).toBe(1);
    expect(heatStep(10, 10)).toBe(5);
  });
});
