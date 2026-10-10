import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Banknote } from "lucide-react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { paiseToINR } from "@/lib/api/applications";
import { PositionCard } from "./position-card";

beforeEach(() => {
  window.matchMedia = ((q: string) => ({
    matches: true, media: q, onchange: null, addListener() {}, removeListener() {},
    addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
});

describe("PositionCard", () => {
  it("shows the title and rupees, a hover popover with loans, and opens its metric on click", async () => {
    const onOpen = vi.fn();
    render(
      <PositionCard title="Pending" tone="orange" icon={Banknote} paise={1_270_000} loans={10}
        metric="POSITION_PENDING" caption="owed today" definition="Still owed." onOpen={onOpen} />,
    );
    const btn = screen.getByRole("button", { name: new RegExp(`^Pending: ${paiseToINR(1_270_000).replace(/[₹.]/g, "\\$&")}`) });
    await userEvent.hover(btn);
    expect(screen.getByRole("tooltip")).toHaveTextContent("10 loans");
    await userEvent.click(btn);
    expect(onOpen).toHaveBeenCalledWith("POSITION_PENDING");
  });

  it("renders zero as ₹0", () => {
    render(<PositionCard title="Total penalty" tone="red" icon={Banknote} paise={0} loans={0}
      metric="POSITION_PENALTY" definition="d" onOpen={() => {}} />);
    expect(screen.getByRole("button", { name: /^Total penalty: ₹0/ })).toBeInTheDocument();
  });
});
