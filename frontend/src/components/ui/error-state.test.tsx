import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ErrorState } from "./error-state";

describe("<ErrorState/>", () => {
  it("announces the failure to assistive tech", async () => {
    // The whole app contained four role="alert"/aria-live instances before this primitive, all of
    // them form errors — so a screen-reader user was never told a register had failed and would
    // read the absence of rows as an empty queue. This is the regression guard for that.
    render(<ErrorState error={new Error("boom")} />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });

  it("offers a retry that calls refetch", async () => {
    const onRetry = vi.fn();
    render(<ErrorState error={new Error("boom")} onRetry={onRetry} />);
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("omits the retry affordance when there is nothing to retry", () => {
    render(<ErrorState error={new Error("boom")} />);
    expect(screen.queryByRole("button", { name: "Try again" })).toBeNull();
  });

  it("derives a message from the error and lets a caller override it", () => {
    render(<ErrorState error={new Error("boom")} title="Couldn't load the register" />);
    expect(screen.getByText("Couldn't load the register")).toBeInTheDocument();
  });

  it("renders as a table row so it is valid inside a tbody", () => {
    // A <div> inside <tbody> is invalid markup the browser hoists out of the table, which silently
    // drops the message above the register instead of inside it.
    const { container } = render(
      <table>
        <tbody>
          <ErrorState error={new Error("boom")} inTable={7} />
        </tbody>
      </table>,
    );
    const cell = container.querySelector("td");
    expect(cell).not.toBeNull();
    expect(cell).toHaveAttribute("colspan", "7");
  });
});
