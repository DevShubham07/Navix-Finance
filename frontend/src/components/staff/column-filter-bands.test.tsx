/**
 * The dropdown-row behaviours of the filter panel: named ranges (`bands`), `startEmpty`, and the
 * server-side lookup for a truncated list. The header caret's Excel-style defaults are pinned in
 * column-filter-panel.test.tsx; these only cover what the new options add.
 */
import { render, screen, fireEvent, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import { FilterPanel, type FilterBand } from "@/components/staff/column-filter";
import type { ColumnFilter } from "@/lib/staff/column-filter-match";

const anchor = new DOMRect(10, 10, 20, 20);
const BANDS: FilterBand[] = [
  { label: "Up to ₹5,000", max: 5000 },
  { label: "₹5,000 to ₹10,000", min: 5000.01, max: 10000 },
  { label: "Above ₹10,000", min: 10000.01 },
];

function numPanel(over: Partial<React.ComponentProps<typeof FilterPanel>> = {}) {
  const onApply = vi.fn();
  render(
    <FilterPanel
      label="Amount"
      kind="num"
      anchor={anchor}
      options={[]}
      filter={undefined}
      onApply={onApply}
      onClose={() => {}}
      bands={BANDS}
      {...over}
    />,
  );
  return onApply;
}

describe("bands", () => {
  it("replace the bare Min/Max boxes with named ranges and a Custom option", () => {
    numPanel();
    expect(screen.getByRole("radio", { name: "Up to ₹5,000" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Custom range" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Minimum")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Maximum")).not.toBeInTheDocument();
  });

  it("apply the chosen band's bounds", () => {
    const onApply = numPanel();
    fireEvent.click(screen.getByRole("radio", { name: "₹5,000 to ₹10,000" }));
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onApply).toHaveBeenCalledWith({ type: "num", min: 5000.01, max: 10000 });
  });

  it("reveal Minimum and Maximum under Custom range", () => {
    const onApply = numPanel();
    fireEvent.click(screen.getByRole("radio", { name: "Custom range" }));
    fireEvent.change(screen.getByLabelText("Minimum"), { target: { value: "1200" } });
    fireEvent.change(screen.getByLabelText("Maximum"), { target: { value: "3400" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onApply).toHaveBeenCalledWith({ type: "num", min: 1200, max: 3400 });
  });

  it("reopen on the band an existing filter matches", () => {
    numPanel({ filter: { type: "num", min: 5000.01, max: 10000 } });
    expect(screen.getByRole("radio", { name: "₹5,000 to ₹10,000" })).toBeChecked();
  });

  it("keep a typed range that is not a band instead of replacing it", () => {
    numPanel({ filter: { type: "num", min: 123, max: 456 } });
    expect(screen.getByRole("radio", { name: "Custom range" })).toBeChecked();
    expect(screen.getByLabelText("Minimum")).toHaveValue(123);
  });

  it("leave a column without bands exactly as it was", () => {
    numPanel({ bands: undefined });
    expect(screen.getByLabelText("Minimum")).toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
  });
});

describe("startEmpty", () => {
  const listProps = { label: "Stage", kind: "in" as const, anchor, options: ["A", "B", "C"], onClose: () => {} };

  it("opens with nothing ticked and treats nothing ticked as no filter, with Apply enabled", () => {
    const onApply = vi.fn();
    render(<FilterPanel {...listProps} filter={undefined} onApply={onApply} startEmpty />);
    for (const box of screen.getAllByRole("checkbox")) expect(box).not.toBeChecked();
    const apply = screen.getByRole("button", { name: "Apply" });
    expect(apply).toBeEnabled();
    fireEvent.click(apply);
    expect(onApply).toHaveBeenCalledWith(undefined);
  });

  it("applies just what was ticked, even when that is every value shown", () => {
    const onApply = vi.fn();
    render(<FilterPanel {...listProps} filter={undefined} onApply={onApply} startEmpty />);
    fireEvent.click(screen.getByLabelText("(Select All)"));
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));
    // Not collapsed to "no filter": in the dropdown row the list shown may be a slice of the column.
    expect(onApply).toHaveBeenCalledWith({ type: "in", values: ["A", "B", "C"] });
  });

  it("does not follow a late value list", () => {
    const { rerender } = render(
      <FilterPanel {...listProps} options={[]} filter={undefined} onApply={() => {}} startEmpty />,
    );
    rerender(<FilterPanel {...listProps} options={["A", "B"]} filter={undefined} onApply={() => {}} startEmpty />);
    for (const box of screen.getAllByRole("checkbox")) expect(box).not.toBeChecked();
  });

  it("keeps the Excel-style start without it (a header caret)", () => {
    render(<FilterPanel {...listProps} filter={undefined} onApply={() => {}} />);
    for (const box of screen.getAllByRole("checkbox")) expect(box).toBeChecked();
  });

  it("reopens on an applied selection", () => {
    const filter: ColumnFilter = { type: "in", values: ["B"] };
    render(<FilterPanel {...listProps} filter={filter} onApply={() => {}} startEmpty />);
    expect(screen.getByLabelText("B")).toBeChecked();
    expect(screen.getByLabelText("A")).not.toBeChecked();
  });
});

describe("server lookup", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const base = {
    label: "Employer",
    kind: "in" as const,
    anchor,
    options: ["Acme", "Beta"],
    filter: undefined,
    onApply: () => {},
    onClose: () => {},
    startEmpty: true,
  };

  it("searches the server, debounced, once the list is truncated", () => {
    const onSearch = vi.fn();
    render(<FilterPanel {...base} truncated onSearch={onSearch} />);
    const box = screen.getByLabelText("Search Employer values");
    fireEvent.change(box, { target: { value: "ac" } });
    fireEvent.change(box, { target: { value: "acm" } });
    expect(onSearch).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(350);
    });
    expect(onSearch).toHaveBeenCalledTimes(1);
    expect(onSearch).toHaveBeenCalledWith("acm");
    // Clearing the box asks for the full list again.
    fireEvent.change(box, { target: { value: "" } });
    act(() => {
      vi.advanceTimersByTime(350);
    });
    expect(onSearch).toHaveBeenLastCalledWith("");
  });

  it("filters locally and never calls the server while the list is complete", () => {
    const onSearch = vi.fn();
    render(<FilterPanel {...base} onSearch={onSearch} />);
    fireEvent.change(screen.getByLabelText("Search Employer values"), { target: { value: "ac" } });
    act(() => {
      vi.advanceTimersByTime(350);
    });
    expect(onSearch).not.toHaveBeenCalled();
    expect(screen.queryByLabelText("Beta")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Acme")).toBeInTheDocument();
  });

  it("keeps earlier picks across searches", () => {
    const onApply = vi.fn();
    const { rerender } = render(<FilterPanel {...base} onApply={onApply} truncated onSearch={() => {}} />);
    fireEvent.click(screen.getByLabelText("Acme"));
    rerender(<FilterPanel {...base} options={["Zed"]} onApply={onApply} truncated onSearch={() => {}} />);
    fireEvent.click(screen.getByLabelText("Zed"));
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));
    expect(onApply).toHaveBeenCalledWith({ type: "in", values: ["Acme", "Zed"] });
  });
});
