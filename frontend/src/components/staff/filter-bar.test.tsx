/**
 * The filter bar's own behaviour: newest-first chip order, the two-sibling-button chip, the panel
 * without sort rows, and that "Clear filters" leaves the search term alone. The harness owns a real
 * filter set (as `useRegisterQuery` would) so a click round-trips through `th.setFilter`.
 */
import { render, screen, fireEvent, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import * as React from "react";
import { FilterBar } from "@/components/staff/filter-bar";
import type { ColumnFilter, ColumnFilterSet } from "@/lib/staff/column-filter-match";
import type { ThBag } from "@/lib/staff/register-th";

const KEYS = ["status", "amount", "due", "name"];
const KINDS: Record<string, ColumnFilter["type"]> = { status: "in", amount: "num", due: "date", name: "text" };
const HEADERS: Record<string, string> = { status: "Status", amount: "Amount", due: "Due", name: "Name" };

const IN = (v: string): ColumnFilter => ({ type: "in", values: [v] });

function Harness({
  initial = {},
  keys = KEYS,
  onSetFilter,
  onClear,
  onOpenFacet,
  total = 7,
}: {
  initial?: ColumnFilterSet;
  keys?: string[];
  onSetFilter?: (key: string, next: ColumnFilter | undefined) => void;
  onClear?: () => void;
  onOpenFacet?: (key: string) => void;
  total?: number;
}) {
  const [filters, setFilters] = React.useState<ColumnFilterSet>(initial);
  const [q] = React.useState("typed search");
  const th = {
    keys,
    filters,
    setFilter: (key: string, next: ColumnFilter | undefined) => {
      onSetFilter?.(key, next);
      // Spread, as register-query does: re-setting a key keeps its original position.
      setFilters((p) => {
        const n = { ...p, [key]: next };
        if (!next) delete n[key];
        return n;
      });
    },
    kindFor: (k: string) => KINDS[k],
    optionsFor: () => ["A", "B", "C"],
    labelsFor: () => new Map([["A", "Alpha"]]),
    headerFor: (k: string) => HEADERS[k],
    onOpenFacet,
  } as unknown as ThBag;
  return (
    <>
      <span data-testid="q">{q}</span>
      <FilterBar th={th} total={total} onClear={() => { onClear?.(); setFilters({}); }} />
    </>
  );
}

const chipHeaders = () =>
  screen.getAllByRole("button", { name: /^Remove / }).map((b) => b.getAttribute("aria-label")!.replace(/^Remove | filter$/g, ""));
const body = (h: string) => screen.getByRole("button", { name: new RegExp(`^${h}\\b`) });
const apply = () => fireEvent.click(screen.getByRole("button", { name: "Apply" }));

/** Add a filter on `key` through the Add filter menu. */
function addVia(key: string) {
  fireEvent.click(screen.getByRole("button", { name: /Add filter/ }));
  fireEvent.click(screen.getByRole("menuitem", { name: HEADERS[key] }));
  const dlg = screen.getByRole("dialog");
  if (KINDS[key] === "in") fireEvent.click(within(dlg).getByLabelText("B"));
  else if (KINDS[key] === "text") fireEvent.change(within(dlg).getByLabelText(/contains/), { target: { value: "x" } });
  else if (KINDS[key] === "date") fireEvent.change(within(dlg).getAllByDisplayValue("")[0], { target: { value: "2026-01-01" } });
  fireEvent.click(within(dlg).getByRole("button", { name: "Apply" }));
}

describe("FilterBar", () => {
  it("shows only Add filter when nothing is filtered", () => {
    render(<Harness />);
    expect(screen.getByRole("button", { name: /Add filter/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Clear filters/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/results/)).not.toBeInTheDocument();
  });

  it("renders nothing without columns", () => {
    const { container } = render(<Harness keys={[]} />);
    expect(container.querySelector("[data-filter-bar]")).toBeNull();
  });

  it("orders chips newest first, moves an edited one to the front, and removal keeps the rest", () => {
    render(<Harness />);
    // labelsFor only knows "A"; the second option has no label so it is listed by raw value.
    for (const k of ["status", "amount", "due"]) {
      fireEvent.click(screen.getByRole("button", { name: /Add filter/ }));
      fireEvent.click(screen.getByRole("menuitem", { name: HEADERS[k] }));
      const dlg = screen.getByRole("dialog");
      if (k === "status") fireEvent.click(within(dlg).getByLabelText("B"));
      else if (k === "amount") fireEvent.change(within(dlg).getByLabelText(/min|from|at least/i, { selector: "input" }), { target: { value: "5" } });
      else fireEvent.change(within(dlg).getAllByDisplayValue("")[0], { target: { value: "2026-01-01" } });
      fireEvent.click(within(dlg).getByRole("button", { name: "Apply" }));
    }
    expect(chipHeaders()).toEqual(["Due", "Amount", "Status"]);

    addVia("name");
    expect(chipHeaders()[0]).toBe("Name");

    // Edit the oldest chip (untick C): it jumps to index 0.
    fireEvent.click(body("Status"));
    fireEvent.click(within(screen.getByRole("dialog")).getByLabelText("C"));
    apply();
    expect(chipHeaders()[0]).toBe("Status");

    fireEvent.click(screen.getByRole("button", { name: "Remove Amount filter" }));
    expect(chipHeaders()).toEqual(["Status", "Name", "Due"]);
  });

  it("puts unordered active filters in column order, and scrolls to the start on insert", () => {
    render(<Harness initial={{ name: IN("A"), status: IN("A") }} />);
    expect(chipHeaders()).toEqual(["Status", "Name"]);

    const scroller = document.querySelector(".pill-tabs") as HTMLElement;
    scroller.scrollLeft = 120;
    addVia("due");
    expect(scroller.scrollLeft).toBe(0);
  });

  it("the cross calls setFilter(key, undefined) and nothing else", () => {
    const onSetFilter = vi.fn();
    const onClear = vi.fn();
    render(<Harness initial={{ status: IN("A") }} onSetFilter={onSetFilter} onClear={onClear} />);
    fireEvent.click(screen.getByRole("button", { name: "Remove Status filter" }));
    expect(onSetFilter).toHaveBeenCalledTimes(1);
    expect(onSetFilter).toHaveBeenCalledWith("status", undefined);
    expect(onClear).not.toHaveBeenCalled();
  });

  it("is two sibling buttons, and the chip body opens a panel with no sort rows", () => {
    render(<Harness initial={{ status: IN("A") }} />);
    const remove = screen.getByRole("button", { name: "Remove Status filter" });
    const bodyBtn = body("Status");
    expect(remove.parentElement).toBe(bodyBtn.parentElement);
    expect(bodyBtn).toHaveAttribute("aria-haspopup", "dialog");
    expect(bodyBtn).toHaveAttribute("aria-expanded", "false");
    // The column name and the value, either side of a divider — the chip drops the "in" that
    // `describeFilter`'s sentence form carries, because the divider already says it.
    expect(bodyBtn).toHaveTextContent("Status");
    expect(bodyBtn).toHaveTextContent("Alpha");
    expect(bodyBtn.textContent).not.toMatch(/\bin\b/);

    fireEvent.click(bodyBtn);
    expect(bodyBtn).toHaveAttribute("aria-expanded", "true");
    const dlg = screen.getByRole("dialog");
    expect(within(dlg).queryByRole("button", { name: /^Sort / })).not.toBeInTheDocument();
    expect(within(dlg).getByRole("button", { name: /Clear filter from/ })).toBeInTheDocument();
  });

  it("Backspace on the body removes the filter", () => {
    const onSetFilter = vi.fn();
    render(<Harness initial={{ status: IN("A") }} onSetFilter={onSetFilter} />);
    fireEvent.keyDown(body("Status"), { key: "Backspace" });
    expect(onSetFilter).toHaveBeenCalledWith("status", undefined);
  });

  it("Add filter lists only unfiltered columns and opens that kind's panel", () => {
    render(<Harness initial={{ status: IN("A") }} />);
    fireEvent.click(screen.getByRole("button", { name: /Add filter/ }));
    expect(screen.getAllByRole("menuitem").map((m) => m.textContent)).toEqual(["Amount", "Due", "Name"]);
    fireEvent.click(screen.getByRole("menuitem", { name: "Name" }));
    expect(within(screen.getByRole("dialog")).getByLabelText(/Name contains/)).toBeInTheDocument();
  });

  it("the Add filter menu closes on an outside click and on Escape, returning focus to the chip", () => {
    render(<Harness />);
    const add = () => screen.getByRole("button", { name: /Add filter/ });
    fireEvent.click(add());
    expect(screen.getByRole("menu")).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    fireEvent.click(add());
    const onDocKey = vi.fn();
    document.addEventListener("keydown", onDocKey); // bubble phase: must never see the Escape
    fireEvent.keyDown(screen.getByRole("menuitem", { name: "Status" }), { key: "Escape" });
    document.removeEventListener("keydown", onDocKey);
    expect(onDocKey).not.toHaveBeenCalled();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(add()).toHaveFocus();
  });

  it("Clear filters clears the filters and leaves the search term", () => {
    const onClear = vi.fn();
    render(<Harness initial={{ status: IN("A") }} onClear={onClear} />);
    fireEvent.click(screen.getByRole("button", { name: /Clear filters/ }));
    expect(onClear).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: /^Remove / })).not.toBeInTheDocument();
    expect(screen.getByTestId("q")).toHaveTextContent("typed search");
  });

  it("fires onOpenFacet exactly once per chip open", () => {
    const onOpenFacet = vi.fn();
    render(<Harness initial={{ status: IN("A") }} onOpenFacet={onOpenFacet} />);
    fireEvent.click(body("Status"));
    expect(onOpenFacet).toHaveBeenCalledTimes(1);
    expect(onOpenFacet).toHaveBeenCalledWith("status");
  });

  it("moves focus to Add filter after a removal", () => {
    render(<Harness initial={{ status: IN("A"), name: IN("A") }} />);
    fireEvent.click(screen.getByRole("button", { name: "Remove Status filter" }));
    expect(screen.getByRole("button", { name: /Add filter/ })).toHaveFocus();
  });
});

describe("the empty-result notice", () => {
  /**
   * It speaks only when the filters have emptied the register. `PaginationBar` already prints the
   * count just below — in the browser the two read as one number printed twice — but it renders
   * NOTHING at `total === 0`, which is the one moment a reader needs telling why the table is blank.
   */
  it("stays quiet while rows match", () => {
    render(<Harness initial={{ status: IN("A") }} total={7} />);
    expect(screen.queryByText(/No rows match/)).not.toBeInTheDocument();
    expect(screen.queryByText(/result/)).not.toBeInTheDocument();
  });

  it("says so when a filter has emptied the register", () => {
    render(<Harness initial={{ status: IN("A") }} total={0} />);
    expect(screen.getByText("No rows match")).toBeInTheDocument();
    // …and the filters that caused it are still right there to clear.
    expect(screen.getByRole("button", { name: /Clear filters/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove Status filter" })).toBeInTheDocument();
  });

  it("says nothing when no filter is active, however many rows there are", () => {
    render(<Harness total={0} />);
    expect(screen.queryByText(/No rows match/)).not.toBeInTheDocument();
  });
});
