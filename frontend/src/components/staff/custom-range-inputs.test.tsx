import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CustomRangeInputs } from "./custom-range-inputs";

/**
 * The point of this component is that a custom range costs ONE fetch. Before it, both period
 * controls committed on every `change` of a native date input — which fires once per typed year digit
 * — so these tests pin "nothing commits until Apply".
 */
describe("<CustomRangeInputs/>", () => {
  const from = () => screen.getByLabelText("From date") as HTMLInputElement;
  const to = () => screen.getByLabelText("To date") as HTMLInputElement;
  const apply = () => screen.getByRole("button", { name: "Apply" });

  it("does not commit while the user is editing", async () => {
    const onApply = vi.fn();
    render(<CustomRangeInputs value={{}} onApply={onApply} />);
    await userEvent.type(from(), "2026-09-01");
    await userEvent.type(to(), "2026-09-30");
    expect(onApply).not.toHaveBeenCalled();
  });

  it("commits the whole range once on Apply", async () => {
    const onApply = vi.fn();
    render(<CustomRangeInputs value={{}} onApply={onApply} />);
    await userEvent.type(from(), "2026-09-01");
    await userEvent.type(to(), "2026-09-30");
    await userEvent.click(apply());
    expect(onApply).toHaveBeenCalledTimes(1);
    expect(onApply).toHaveBeenCalledWith({ from: "2026-09-01", to: "2026-09-30" });
  });

  it("commits on Enter", async () => {
    const onApply = vi.fn();
    render(<CustomRangeInputs value={{}} onApply={onApply} />);
    await userEvent.type(from(), "2026-09-01{Enter}");
    expect(onApply).toHaveBeenCalledWith({ from: "2026-09-01" });
  });

  it("refuses an inverted range and says why", async () => {
    const onApply = vi.fn();
    render(<CustomRangeInputs value={{}} onApply={onApply} />);
    await userEvent.type(from(), "2026-09-30");
    await userEvent.type(to(), "2026-09-01");
    expect(apply()).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("after");
    await userEvent.click(apply());
    expect(onApply).not.toHaveBeenCalled();
  });

  it("disables Apply when nothing changed, so it cannot fire a no-op request", () => {
    render(<CustomRangeInputs value={{ from: "2026-09-01", to: "2026-09-30" }} onApply={vi.fn()} />);
    expect(apply()).toBeDisabled();
  });

  it("follows a committed value set from outside (a preset or a deep link)", () => {
    const { rerender } = render(<CustomRangeInputs value={{ from: "2026-09-01" }} onApply={vi.fn()} />);
    expect(from().value).toBe("2026-09-01");
    rerender(<CustomRangeInputs value={{ from: "2026-08-01" }} onApply={vi.fn()} />);
    expect(from().value).toBe("2026-08-01");
  });
});
