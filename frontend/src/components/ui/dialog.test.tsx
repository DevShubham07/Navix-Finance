import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Dialog } from "./dialog";
import { Drawer } from "./drawer";

describe("<Dialog/>", () => {
  it("traps Tab inside the panel", async () => {
    // Before this, Dialog had Escape and nothing else: a keyboard user could tab straight out of
    // an open modal into the register behind it. Drawer already had the trap.
    render(
      <>
        <button type="button">outside</button>
        <Dialog open onClose={() => {}} aria-label="Test dialog">
          <button type="button">first</button>
          <button type="button">last</button>
        </Dialog>
      </>,
    );
    const first = screen.getByRole("button", { name: "first" });
    const last = screen.getByRole("button", { name: "last" });

    expect(first).toHaveFocus();
    await userEvent.tab();
    expect(last).toHaveFocus();
    // Wrapping forward from the last focusable returns to the first, never to "outside".
    await userEvent.tab();
    expect(first).toHaveFocus();
  });

  it("restores focus to the trigger when it closes", () => {
    // Focus restore is exercised through the hook's contract: deactivating the trap returns focus
    // to whatever held it at activation — which is what makes a modal usable from the keyboard.
    const trigger = document.createElement("button");
    document.body.appendChild(trigger);
    trigger.focus();
    expect(trigger).toHaveFocus();

    const { unmount } = render(
      <Dialog open onClose={() => {}} aria-label="Test dialog">
        <button type="button">inside</button>
      </Dialog>,
    );
    expect(screen.getByRole("button", { name: "inside" })).toHaveFocus();
    unmount();
    expect(trigger).toHaveFocus();
    trigger.remove();
  });

  it("locks body scroll while open and restores it on close", () => {
    expect(document.body.style.overflow).toBe("");
    const { unmount } = render(
      <Dialog open onClose={() => {}} aria-label="Test dialog">
        <button type="button">inside</button>
      </Dialog>,
    );
    expect(document.body.style.overflow).toBe("hidden");
    unmount();
    expect(document.body.style.overflow).toBe("");
  });

  it("closes on Escape", async () => {
    const onClose = vi.fn();
    render(
      <Dialog open onClose={onClose} aria-label="Test dialog">
        <button type="button">inside</button>
      </Dialog>,
    );
    await userEvent.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalled();
  });

  it("applies a size without the call site inventing a !max-w override", () => {
    render(
      <Dialog open onClose={() => {}} size="xl" aria-label="Test dialog">
        <span>body</span>
      </Dialog>,
    );
    expect(screen.getByRole("dialog")).toHaveClass("!max-w-[80vw]");
  });
});

describe("<Drawer/>", () => {
  it("renders into document.body, not in place", async () => {
    // The Journey drawer mounts from inside a queue row's `position: sticky` actions cell, whose
    // non-auto z-index establishes a stacking context — so an in-place panel was painted over by
    // the sticky navy header cells (z-index 3). The portal is what makes a sticky thead safe.
    const { container } = render(
      <div data-testid="sticky-cell">
        <Drawer open onClose={() => {}} aria-label="Test drawer">
          <button type="button">inside drawer</button>
        </Drawer>
      </div>,
    );
    const panel = await screen.findByRole("dialog");
    expect(panel).toBeInTheDocument();
    // The panel must NOT be a descendant of the cell it was declared in.
    expect(container.querySelector('[data-testid="sticky-cell"]')?.contains(panel)).toBe(false);
    expect(document.body.contains(panel)).toBe(true);
  });
});
