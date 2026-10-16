/**
 * The AutoFilter panel. This component shipped with no test at all, and it is about to be the
 * filtering surface for every staff register, so these pin the behaviours that are easy to break:
 * staged commits, the "everything ticked is no filter" rule, each kind's body, and the two defects
 * that made it unusable inside a dialog (Escape closing the dialog underneath, and the panel being
 * unreachable by keyboard).
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import * as React from "react";
import { FilterableTh, FilterPanel, useColumnFilters, type FilterColumn } from "@/components/staff/column-filter";
import type { ColumnFilter } from "@/lib/staff/column-filter-match";

interface Row {
  name: string;
  role: string;
  amount: number;
  due: string;
}

const ROWS: Row[] = [
  { name: "Asha", role: "CREDIT_EXECUTIVE", amount: 1000, due: "2026-01-10" },
  { name: "Bela", role: "CREDIT_HEAD", amount: 5000, due: "2026-02-10" },
  { name: "Chen", role: "CREDIT_EXECUTIVE", amount: 9000, due: "2026-03-10" },
];

const ROLE_LABEL: Record<string, string> = {
  CREDIT_EXECUTIVE: "Credit Executive",
  CREDIT_HEAD: "Credit Head",
};

/** A one-column harness so a test can drive the panel without a whole register page. */
function Harness({
  kind = "in",
  columnKey = "role",
  onFilters,
  onOpen,
  sortable,
}: {
  kind?: ColumnFilter["type"];
  columnKey?: string;
  onFilters?: (f: Record<string, ColumnFilter | undefined>) => void;
  onOpen?: () => void;
  sortable?: boolean;
}) {
  const columns = React.useMemo<Array<FilterColumn<Row>>>(
    () => [
      { key: "name", value: (r) => r.name, kind: "text" },
      { key: "role", value: (r) => ROLE_LABEL[r.role] ?? r.role, raw: (r) => r.role },
      { key: "amount", value: (r) => String(r.amount), kind: "num", num: (r) => r.amount },
      { key: "due", value: (r) => r.due, kind: "date", date: (r) => r.due },
    ],
    [],
  );
  const { filtered, filterFor, labelsFor, optionsFor, setFilter, filters } = useColumnFilters(ROWS, columns);
  React.useEffect(() => {
    onFilters?.(filters);
  }, [filters, onFilters]);

  return (
    <table>
      <thead>
        <tr>
          <FilterableTh
            label="Column"
            sortKey={columnKey}
            active=""
            dir="asc"
            onToggle={() => {}}
            setSort={() => {}}
            kind={kind}
            options={optionsFor(columnKey)}
            labels={labelsFor(columnKey)}
            filter={filterFor(columnKey)}
            onApply={(v) => setFilter(columnKey, v)}
            onOpen={onOpen}
            sortable={sortable}
          />
        </tr>
      </thead>
      <tbody>
        {filtered.map((r) => (
          <tr key={r.name}><td>{r.name}</td></tr>
        ))}
      </tbody>
    </table>
  );
}

const openPanel = () => fireEvent.click(screen.getByRole("button", { name: /^Filter Column/ }));
const panel = () => screen.getByRole("dialog", { name: /Filter and sort Column/ });
const names = () => screen.getAllByRole("cell").map((c) => c.textContent);

describe("the checklist panel", () => {
  it("shows labels, not stored values", () => {
    render(<Harness />);
    openPanel();
    expect(within(panel()).getByLabelText("Credit Executive")).toBeInTheDocument();
    expect(within(panel()).queryByLabelText("CREDIT_EXECUTIVE")).not.toBeInTheDocument();
  });

  it("stages edits — nothing changes until OK, and Cancel discards", () => {
    render(<Harness />);
    openPanel();
    fireEvent.click(within(panel()).getByLabelText("Credit Head"));
    // Still three rows: the tick has changed nothing yet.
    expect(names()).toEqual(["Asha", "Bela", "Chen"]);

    fireEvent.click(within(panel()).getByRole("button", { name: "Cancel" }));
    expect(names()).toEqual(["Asha", "Bela", "Chen"]);

    openPanel();
    fireEvent.click(within(panel()).getByLabelText("Credit Head"));
    fireEvent.click(within(panel()).getByRole("button", { name: "Apply" }));
    expect(names()).toEqual(["Asha", "Chen"]);
  });

  it("filters on the stored value", () => {
    const seen: Array<Record<string, ColumnFilter | undefined>> = [];
    render(<Harness onFilters={(f) => seen.push(f)} />);
    openPanel();
    fireEvent.click(within(panel()).getByLabelText("Credit Head"));
    fireEvent.click(within(panel()).getByRole("button", { name: "Apply" }));
    expect(seen.at(-1)?.role).toEqual({ type: "in", values: ["CREDIT_EXECUTIVE"] });
  });

  it("treats everything ticked as no filter at all", () => {
    const seen: Array<Record<string, ColumnFilter | undefined>> = [];
    render(<Harness onFilters={(f) => seen.push(f)} />);
    openPanel();
    // Untick then re-tick: back to all, which must clear rather than store a no-op.
    fireEvent.click(within(panel()).getByLabelText("Credit Head"));
    fireEvent.click(within(panel()).getByLabelText("Credit Head"));
    fireEvent.click(within(panel()).getByRole("button", { name: "Apply" }));
    expect(seen.at(-1)?.role).toBeUndefined();
  });

  it("refuses to commit an empty selection", () => {
    render(<Harness />);
    openPanel();
    fireEvent.click(within(panel()).getByLabelText("(Select All)"));
    expect(within(panel()).getByRole("button", { name: "Apply" })).toBeDisabled();
  });
});

describe("the range panels", () => {
  it("bounds a number column, inclusive", () => {
    render(<Harness kind="num" columnKey="amount" />);
    openPanel();
    fireEvent.change(within(panel()).getByLabelText("Minimum"), { target: { value: "5000" } });
    fireEvent.click(within(panel()).getByRole("button", { name: "Apply" }));
    expect(names()).toEqual(["Bela", "Chen"]);
  });

  it("will not commit a range that is upside down", () => {
    render(<Harness kind="num" columnKey="amount" />);
    openPanel();
    fireEvent.change(within(panel()).getByLabelText("Minimum"), { target: { value: "9000" } });
    fireEvent.change(within(panel()).getByLabelText("Maximum"), { target: { value: "1000" } });
    expect(within(panel()).getByRole("button", { name: "Apply" })).toBeDisabled();
    expect(within(panel()).getByText("Minimum is above the maximum.")).toBeInTheDocument();
  });

  it("windows a date column, and a preset fills both ends", () => {
    render(<Harness kind="date" columnKey="due" />);
    openPanel();
    fireEvent.change(within(panel()).getByLabelText("From"), { target: { value: "2026-02-01" } });
    fireEvent.click(within(panel()).getByRole("button", { name: "Apply" }));
    expect(names()).toEqual(["Bela", "Chen"]);

    openPanel();
    fireEvent.click(within(panel()).getByRole("button", { name: "Today" }));
    expect((within(panel()).getByLabelText("From") as HTMLInputElement).value).not.toBe("");
    expect((within(panel()).getByLabelText("To") as HTMLInputElement).value).not.toBe("");
  });

  it("matches a text column on the label", () => {
    render(<Harness kind="text" columnKey="name" />);
    openPanel();
    fireEvent.change(within(panel()).getByLabelText("Column contains"), { target: { value: "be" } });
    fireEvent.click(within(panel()).getByRole("button", { name: "Apply" }));
    expect(names()).toEqual(["Bela"]);
  });
});

describe("living inside a dialog", () => {
  it("does not close the surface underneath it on Escape", () => {
    const onDialogEscape = vi.fn();
    render(
      <div
        onKeyDown={(e) => {
          if (e.key === "Escape") onDialogEscape();
        }}
      >
        <Harness />
      </div>,
    );
    openPanel();
    fireEvent.keyDown(panel(), { key: "Escape" });
    // The panel is gone; the dialog's own handler never heard about it.
    expect(screen.queryByRole("dialog", { name: /Filter and sort Column/ })).not.toBeInTheDocument();
    expect(onDialogEscape).not.toHaveBeenCalled();
  });

  it("announces itself as modal so the dialog's last-modal check defers to it", () => {
    render(<Harness />);
    openPanel();
    expect(panel()).toHaveAttribute("aria-modal", "true");
  });

  it("tags itself with the trapped surface that owns it, so the focus trap can find it", () => {
    render(
      <div id="owner-dialog" aria-modal="true" role="dialog">
        <Harness />
      </div>,
    );
    openPanel();
    expect(panel()).toHaveAttribute("data-trap-owner", "owner-dialog");
  });
});

describe("onOpen", () => {
  /** The server-paged registers hang a facet fetch off this, so it must fire on the open and only
   *  on the open — `options` is read every render and cannot be the trigger. */
  it("fires once when the panel opens, and not again until it is reopened", () => {
    const onOpen = vi.fn();
    render(<Harness onOpen={onOpen} />);

    openPanel();
    expect(onOpen).toHaveBeenCalledTimes(1);

    // Still open: clicking inside it must not re-fetch.
    fireEvent.click(within(panel()).getByLabelText("Credit Head"));
    expect(onOpen).toHaveBeenCalledTimes(1);

    fireEvent.click(within(panel()).getByRole("button", { name: "Cancel" }));
    expect(onOpen).toHaveBeenCalledTimes(1);

    openPanel();
    expect(onOpen).toHaveBeenCalledTimes(2);
  });

  it("is optional — a client-side register has every value already", () => {
    render(<Harness />);
    expect(() => openPanel()).not.toThrow();
  });
});

describe("a column that cannot be sorted", () => {
  /** The admin register answers in one order and has no sort parameter. A header that showed a
   *  chevron, took a click and reordered nothing would be worse than one that never offered. */
  it("renders the label without a sort control, and still offers the filter", () => {
    render(<Harness sortable={false} />);
    expect(screen.queryByRole("button", { name: /^Sort by/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Filter Column/ })).toBeInTheDocument();
  });

  it("carries no aria-sort, which would tell a screen reader it is sortable", () => {
    render(<Harness sortable={false} />);
    expect(screen.getByRole("columnheader")).not.toHaveAttribute("aria-sort");
  });

  it("still sorts by default", () => {
    render(<Harness />);
    expect(screen.getByRole("button", { name: /^Sort by/ })).toBeInTheDocument();
    expect(screen.getByRole("columnheader")).toHaveAttribute("aria-sort", "none");
  });
});

describe("a checklist whose values arrive after the panel opens", () => {
  /**
   * The server-paged registers fetch a column's values from the facets endpoint, and only once the
   * panel opens — so `options` is EMPTY at mount and arrives a moment later. The draft used to be
   * seeded once, in a lazy initialiser, which left nothing ticked; and because OK is disabled on an
   * empty checklist, the panel was unusable on first open on every server-paged register.
   */
  function LateOptions({ onApply }: { onApply: (f: ColumnFilter | undefined) => void }) {
    const [options, setOptions] = React.useState<string[]>([]);
    React.useEffect(() => {
      const t = setTimeout(() => setOptions(["CREDIT_EXECUTIVE", "CREDIT_HEAD"]), 0);
      return () => clearTimeout(t);
    }, []);
    return (
      <table>
        <thead>
          <tr>
            <FilterableTh
              label="Column"
              sortKey="role"
              active=""
              dir="asc"
              onToggle={() => {}}
              setSort={() => {}}
              options={options}
              labels={new Map(Object.entries(ROLE_LABEL))}
              filter={undefined}
              onApply={onApply}
            />
          </tr>
        </thead>
      </table>
    );
  }

  it("ticks them all as they arrive, so OK is usable on first open", async () => {
    render(<LateOptions onApply={() => {}} />);
    openPanel();
    // Nothing to tick yet.
    expect(within(panel()).getByRole("button", { name: "Apply" })).toBeDisabled();

    await waitFor(() => expect(within(panel()).getByLabelText("Credit Head")).toBeChecked());
    expect(within(panel()).getByLabelText("Credit Executive")).toBeChecked();
    expect(within(panel()).getByRole("button", { name: "Apply" })).toBeEnabled();
  });

  it("does not overwrite what the operator already ticked", async () => {
    const onApply = vi.fn();
    render(<LateOptions onApply={onApply} />);
    openPanel();
    await waitFor(() => expect(within(panel()).getByLabelText("Credit Head")).toBeChecked());

    // Untick one, then let another render go by: the arriving list must not re-tick it.
    fireEvent.click(within(panel()).getByLabelText("Credit Head"));
    await waitFor(() => expect(within(panel()).getByLabelText("Credit Head")).not.toBeChecked());

    fireEvent.click(within(panel()).getByRole("button", { name: "Apply" }));
    expect(onApply).toHaveBeenCalledWith({ type: "in", values: ["CREDIT_EXECUTIVE"] });
  });
});

describe("sort rows", () => {
  const props = {
    label: "Column",
    kind: "in" as const,
    anchor: new DOMRect(10, 10, 20, 20),
    options: ["A"],
    filter: undefined,
    onApply: () => {},
    onClose: () => {},
  };

  it("are rendered only when onSort is supplied", () => {
    const { unmount } = render(<FilterPanel {...props} />);
    expect(screen.queryByRole("button", { name: /^Sort / })).not.toBeInTheDocument();
    unmount();
    render(<FilterPanel {...props} onSort={() => {}} />);
    expect(screen.getByRole("button", { name: "Sort A to Z" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sort Z to A" })).toBeInTheDocument();
  });
});
