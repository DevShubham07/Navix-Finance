import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { TrustStars } from "./trust-stars";

describe("TrustStars", () => {
  it("renders three distinct states with one aria summary", () => {
    const { container } = render(<TrustStars trust={{ bureau: "PASS", uan: "FAIL", email: "NOT_CHECKED" }} />);
    expect(screen.getByRole("button", { name: /trust signals/i }).getAttribute("aria-label")).toBe(
      "Trust signals: Bureau: no DPD in last 6 months; UAN not verified; Work email not checked",
    );
    const cls = Array.from(container.querySelectorAll("svg")).map((s) => s.getAttribute("class"));
    expect(new Set(cls).size).toBe(3);
    expect(cls[0]).toContain("fill-current");
    expect(container.querySelector("[title]")).toBeNull();
  });

  it("explains each star with the backend reason, else the generic text", () => {
    render(
      <TrustStars
        trust={{ bureau: "PASS", uan: "FAIL", email: "NOT_CHECKED", bureauWhy: "Score 780, no DPD", uanWhy: null }}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /trust signals/i }));
    const tip = screen.getByRole("tooltip");
    expect(tip.textContent).toContain("Score 780, no DPD");
    expect(tip.textContent).toContain("UAN not verified");
    expect(tip.textContent).toContain("Work email not checked");
    expect(tip.textContent).toContain("Passed");
    expect(tip.textContent).toContain("Failed");
  });

  it("falls back to not-checked when trust is null", () => {
    render(<TrustStars trust={null} />);
    expect(screen.getByRole("button", { name: /trust signals/i }).getAttribute("aria-label")).toContain("no report yet");
    fireEvent.mouseEnter(screen.getByRole("button", { name: /trust signals/i }).parentElement!);
    const tip = screen.getByRole("tooltip");
    expect(tip.textContent?.match(/Not checked/g)).toHaveLength(3);
    expect(tip.textContent).toContain("Bureau: no report yet");
  });

  it("keeps the card open on hover then click, closes on a second click", () => {
    render(<TrustStars trust={null} />);
    const b = screen.getByRole("button", { name: /trust signals/i });
    fireEvent.mouseEnter(b.parentElement!);
    fireEvent.click(b);
    expect(screen.queryByRole("tooltip")).not.toBeNull();
    fireEvent.click(b);
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  it("falls back to generic text for an empty reason", () => {
    render(<TrustStars trust={{ bureau: "PASS", uan: "PASS", email: "PASS", uanWhy: "" }} />);
    fireEvent.click(screen.getByRole("button", { name: /trust signals/i }));
    expect(screen.getByRole("tooltip").textContent).toContain("UAN verified");
  });

  it("does not propagate trigger clicks to a parent", () => {
    const onRow = vi.fn();
    render(
      <div onClick={onRow}>
        <TrustStars trust={null} />
      </div>,
    );
    fireEvent.click(screen.getByRole("button", { name: /trust signals/i }));
    expect(onRow).not.toHaveBeenCalled();
  });
});
