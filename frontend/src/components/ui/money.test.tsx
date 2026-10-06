import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { Money } from "./money";

describe("<Money/>", () => {
  it("formats integer paise as rupees", () => {
    render(<Money paise={1_000_000} />);
    // ₹10,000 — the worked example from CLAUDE.md §9.
    expect(screen.getByText("₹10,000")).toBeInTheDocument();
  });

  it("renders an em dash for an unmeasured value, never ₹0", () => {
    // An unmeasured value must not read as a measured zero — the same rule the performance page's
    // fabricated-zero bug breaks.
    render(<Money paise={null} />);
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("right-aligns tabular figures so a column can be scanned", () => {
    const { container } = render(<Money paise={1234} />);
    const el = container.firstElementChild;
    expect(el).toHaveClass("text-right");
    expect(el).toHaveClass("tabular-nums");
    expect(el).toHaveClass("whitespace-nowrap");
  });

  it("yields alignment to the parent when asked", () => {
    const { container } = render(<Money paise={1234} align={false} />);
    expect(container.firstElementChild).not.toHaveClass("text-right");
  });
});
