import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PillTabs } from "./pill-tabs";

const tabs = [
  { key: "a", label: "Alpha" },
  { key: "b", label: "Beta", disabled: true },
  { key: "c", label: "Gamma" },
];

describe("<PillTabs/>", () => {
  it("marks the active tab selected", () => {
    render(<PillTabs tabs={tabs} active="a" onChange={() => {}} />);
    expect(screen.getByRole("tab", { name: "Alpha" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Gamma" })).toHaveAttribute("aria-selected", "false");
  });

  it("ignores disabled tabs, fires for enabled ones", async () => {
    const onChange = vi.fn();
    render(<PillTabs tabs={tabs} active="a" onChange={onChange} />);
    await userEvent.click(screen.getByRole("tab", { name: "Beta" }));
    expect(onChange).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("tab", { name: "Gamma" }));
    expect(onChange).toHaveBeenCalledWith("c");
  });

  it("renders empty tabs without throwing", () => {
    render(<PillTabs tabs={[]} active="" onChange={() => {}} />);
    expect(screen.queryAllByRole("tab")).toHaveLength(0);
  });
});
