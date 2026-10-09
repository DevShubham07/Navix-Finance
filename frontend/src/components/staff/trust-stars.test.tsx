import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TrustStars } from "./trust-stars";

describe("TrustStars", () => {
  it("renders three distinct states with tooltips and one aria summary", () => {
    const { container } = render(<TrustStars trust={{ bureau: "PASS", uan: "FAIL", email: "NOT_CHECKED" }} />);
    const g = screen.getByRole("img");
    expect(g.getAttribute("aria-label")).toBe(
      "Bureau: no DPD in last 6 months; UAN not verified; Work email not checked",
    );
    const cls = Array.from(container.querySelectorAll("svg")).map((s) => s.getAttribute("class"));
    expect(new Set(cls).size).toBe(3);
    expect(cls[0]).toContain("fill-current");
    expect(container.querySelector('[title="UAN not verified"]')).not.toBeNull();
  });

  it("falls back to not-checked when trust is null", () => {
    render(<TrustStars trust={null} />);
    expect(screen.getByRole("img").getAttribute("aria-label")).toContain("no report yet");
  });
});
