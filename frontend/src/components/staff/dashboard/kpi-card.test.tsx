import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FileText } from "lucide-react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DashKpi } from "@/lib/api/applications";
import { KpiCard } from "./kpi-card";

const kpi: DashKpi = {
  metric: "DISBURSED",
  count: 12,
  amountPaise: 36_660_000,
  fresh: 4,
  reloan: 8,
  freshPaise: 9_300_000,
  reloanPaise: 27_360_000,
  previousCount: 10,
  previousAmountPaise: 30_000_000,
};

beforeEach(() => {
  // Reduced motion: the count-up renders its final value immediately.
  window.matchMedia = ((q: string) => ({
    matches: true,
    media: q,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
});

describe("KpiCard", () => {
  it("shows the count, the fresh / re-loan split and a delta against the previous period", () => {
    render(<KpiCard title="Disbursed Cases" tone="emerald" icon={FileText} kpi={kpi} onOpen={() => {}} />);
    const btn = screen.getByRole("button", { name: /Disbursed Cases: 12\. Fresh 4, re-loan 8/ });
    expect(btn).toHaveTextContent("12");
    expect(btn).toHaveTextContent("33.3%"); // fresh share
    expect(btn).toHaveTextContent("66.7%"); // re-loan share
    expect(btn).toHaveTextContent("20.0%"); // 12 vs 10
  });

  it("hover reveals a popover with exact fresh / re-loan / previous figures", () => {
    const { container } = render(
      <KpiCard
        title="Disbursed Cases"
        tone="emerald"
        icon={FileText}
        kpi={kpi}
        shareLabel="12.5% of applications"
        onOpen={() => {}}
      />,
    );
    const wrapper = container.firstElementChild as Element;
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    fireEvent.mouseEnter(wrapper);
    const tip = screen.getByRole("tooltip");
    expect(tip).toHaveTextContent("4 · ₹93,000");
    expect(tip).toHaveTextContent("8 · ₹2,73,600");
    expect(tip).toHaveTextContent("10 · ₹3,00,000"); // previous period
    expect(tip).toHaveTextContent("12.5% of applications");
    fireEvent.mouseLeave(wrapper);
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  it("click opens the records drawer for the card's metric", () => {
    const onOpen = vi.fn();
    render(<KpiCard title="Disbursed Cases" tone="emerald" icon={FileText} kpi={kpi} onOpen={onOpen} />);
    fireEvent.click(screen.getByRole("button"));
    expect(onOpen).toHaveBeenCalledWith("DISBURSED");
  });

  it("Enter on the focused card opens the drawer too", async () => {
    const onOpen = vi.fn();
    render(<KpiCard title="Disbursed Cases" tone="emerald" icon={FileText} kpi={kpi} onOpen={onOpen} />);
    const user = userEvent.setup();
    await user.tab();
    expect(screen.getByRole("button")).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen).toHaveBeenCalledWith("DISBURSED");
  });

  it("money mode puts the rupee amount on the card", () => {
    render(<KpiCard title="Total Loan Amount" tone="violet" icon={FileText} kpi={kpi} mode="money" onOpen={() => {}} />);
    expect(screen.getByRole("button", { name: /Total Loan Amount: ₹3,66,600/ })).toBeInTheDocument();
  });
});
