import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { Skeleton } from "./skeleton";

describe("<Skeleton/>", () => {
  it("renders the requested number of table rows so the placeholder matches the register", () => {
    // The point of `variant="table"` is that the skeleton has the *shape* of the table it covers,
    // so the header does not jump when real rows arrive.
    const { container } = render(<Skeleton variant="table" rows={6} cols={4} />);
    const rows = container.querySelectorAll(".border-b");
    expect(rows).toHaveLength(6);
    expect(rows[0].querySelectorAll(".skeleton")).toHaveLength(4);
  });

  it("zebra-stripes alternate rows like the register does", () => {
    const { container } = render(<Skeleton variant="table" rows={4} />);
    const rows = Array.from(container.querySelectorAll(".border-b"));
    expect(rows[0].className).not.toContain("bg-grey-100");
    expect(rows[1].className).toContain("bg-grey-100");
  });

  it("is hidden from assistive tech on every variant", () => {
    // A pulsing placeholder is noise to a screen reader; the live region that matters is
    // ErrorState's. Each variant is checked because each returns from a different branch.
    for (const variant of ["line", "stat", "row", "table"] as const) {
      const { container, unmount } = render(<Skeleton variant={variant} />);
      expect(container.firstElementChild).toHaveAttribute("aria-hidden", "true");
      unmount();
    }
  });

  it("carries the .skeleton hook the reduced-motion rule targets", () => {
    const { container } = render(<Skeleton variant="stat" />);
    expect(container.querySelector(".skeleton")).not.toBeNull();
  });
});
