/**
 * The register view owner. The behaviours pinned here are the ones every register page used to
 * re-implement by hand, and the ones the existing customers / leads / transactions tests already
 * assert in page form — the point of this hook is that they stop being per-page discipline.
 */
import { render, screen, act, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import { useRegisterQuery, type RegisterQuery } from "@/lib/staff/register-query";
import { registerViewKey } from "@/lib/staff/register-view-store";

const replace = vi.fn();
let search = "";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/staff/loans",
  useSearchParams: () => new URLSearchParams(search),
}));

const OPTS = {
  registerId: "loans",
  staffId: "7",
  sortKeys: ["dueDate", "amount"] as const,
  filterKeys: ["status", "amount"] as const,
  defaults: { sort: { key: "dueDate", dir: "desc" as const }, size: 25 },
};

let rq: RegisterQuery;

/** `anonymous` stands for the real case: the session has not resolved yet, so there is no staff id.
 *  A default parameter cannot express it — passing `staffId={undefined}` would fall back to "7". */
function Harness({ anonymous = false }: { anonymous?: boolean } = {}) {
  rq = useRegisterQuery({ ...OPTS, staffId: anonymous ? undefined : "7" });
  return (
    <div>
      <span data-testid="page">{rq.state.page}</span>
      <span data-testid="q">{rq.state.q}</span>
      <span data-testid="sort">{`${rq.state.sort.key}:${rq.state.sort.dir}`}</span>
      <span data-testid="active">{rq.activeCount}</span>
      <span data-testid="params">{JSON.stringify(rq.queryParams)}</span>
    </div>
  );
}

beforeEach(() => {
  replace.mockClear();
  search = "";
  localStorage.clear();
});

afterEach(() => {
  localStorage.clear();
});

describe("the page-1 rule", () => {
  it("returns to page 1 on a filter change, in the same update", () => {
    render(<Harness />);
    act(() => rq.setPage(4));
    expect(screen.getByTestId("page")).toHaveTextContent("4");

    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    // One transition: the filter and the page move together, so only one query key ever exists for
    // the new filter — there is no render in between carrying the new filter at the old page.
    expect(screen.getByTestId("page")).toHaveTextContent("1");
  });

  it("returns to page 1 on a search, a sort and a page-size change", () => {
    render(<Harness />);
    for (const change of [
      () => rq.setQ("ravi"),
      () => rq.setSort("amount", "asc"),
      () => rq.setSize(50),
    ]) {
      act(() => rq.setPage(3));
      act(change);
      expect(screen.getByTestId("page")).toHaveTextContent("1");
    }
  });

  it("keeps the page when only the page changes", () => {
    render(<Harness />);
    act(() => rq.setPage(2));
    expect(screen.getByTestId("page")).toHaveTextContent("2");
  });
});

describe("the URL", () => {
  it("writes the view, leaving defaults out", () => {
    render(<Harness />);
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    const url = replace.mock.calls.at(-1)?.[0] as string;
    expect(url).toContain("f.status=in%3AACTIVE");
    // Default sort and page 1 are not worth carrying.
    expect(url).not.toContain("sort=dueDate");
    expect(url).not.toContain("page=1");
  });

  it("does not replace when nothing changed", () => {
    render(<Harness />);
    expect(replace).not.toHaveBeenCalled();
  });

  it("reads a shared link back", () => {
    search = "f.status=in%3AACTIVE&sort=amount&dir=asc&page=3&q=ravi";
    render(<Harness />);
    expect(screen.getByTestId("page")).toHaveTextContent("3");
    expect(screen.getByTestId("q")).toHaveTextContent("ravi");
    expect(screen.getByTestId("sort")).toHaveTextContent("amount:asc");
    expect(screen.getByTestId("active")).toHaveTextContent("2"); // one filter + the search
  });

  it("ignores a column this register does not have", () => {
    search = "f.nonsense=in%3AX";
    render(<Harness />);
    expect(screen.getByTestId("active")).toHaveTextContent("0");
  });
});

describe("the remembered view", () => {
  it("restores the last view when the URL says nothing", () => {
    localStorage.setItem(registerViewKey("7", "loans"), "f.status=in%3ACLOSED&sort=amount&dir=asc");
    render(<Harness />);
    expect(screen.getByTestId("sort")).toHaveTextContent("amount:asc");
    expect(screen.getByTestId("active")).toHaveTextContent("1");
  });

  it("lets a shared link win over it", () => {
    localStorage.setItem(registerViewKey("7", "loans"), "f.status=in%3ACLOSED");
    search = "f.status=in%3AACTIVE";
    render(<Harness />);
    // The sender's view, not the recipient's.
    expect(JSON.parse(screen.getByTestId("params").textContent ?? "{}")["f.status"]).toBe("in:ACTIVE");
  });

  it("survives a corrupt or blocked store", () => {
    localStorage.setItem(registerViewKey("7", "loans"), "%%%not a query string%%%");
    expect(() => render(<Harness />)).not.toThrow();
    expect(screen.getByTestId("sort")).toHaveTextContent("dueDate:desc");
  });

  it("restores it when the staff id arrives a render later", async () => {
    // The real timing: `useStaffMe()` resolves AFTER the first render, so the reducer's initialiser
    // cannot see the stored view. Found by running the app — the unit tests all handed the id over
    // synchronously, and the register silently opened unfiltered every time.
    localStorage.setItem(registerViewKey("7", "loans"), "f.status=in%3ACLOSED");

    function Late() {
      const [staffId, setStaffId] = React.useState<string | undefined>(undefined);
      React.useEffect(() => {
        const t = setTimeout(() => setStaffId("7"), 0);
        return () => clearTimeout(t);
      }, []);
      rq = useRegisterQuery({ ...OPTS, staffId });
      return <span data-testid="active">{rq.activeCount}</span>;
    }

    render(<Late />);
    expect(screen.getByTestId("active")).toHaveTextContent("0");
    await waitFor(() => expect(screen.getByTestId("active")).toHaveTextContent("1"));
  });

  it("does not wipe the remembered view before it has had a chance to restore", async () => {
    localStorage.setItem(registerViewKey("7", "loans"), "f.status=in%3ACLOSED");

    function Late() {
      const [staffId, setStaffId] = React.useState<string | undefined>(undefined);
      React.useEffect(() => {
        const t = setTimeout(() => setStaffId("7"), 0);
        return () => clearTimeout(t);
      }, []);
      rq = useRegisterQuery({ ...OPTS, staffId });
      return <span data-testid="active">{rq.activeCount}</span>;
    }

    render(<Late />);
    await waitFor(() => expect(screen.getByTestId("active")).toHaveTextContent("1"));
    expect(localStorage.getItem(registerViewKey("7", "loans"))).toBe("f.status=in%3ACLOSED");
  });

  it("keeps what the operator did if they act before the session resolves", async () => {
    localStorage.setItem(registerViewKey("7", "loans"), "f.status=in%3ACLOSED");

    function Late() {
      const [staffId, setStaffId] = React.useState<string | undefined>(undefined);
      React.useEffect(() => {
        const t = setTimeout(() => setStaffId("7"), 10);
        return () => clearTimeout(t);
      }, []);
      rq = useRegisterQuery({ ...OPTS, staffId });
      return <span data-testid="params">{JSON.stringify(rq.queryParams)}</span>;
    }

    render(<Late />);
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    await new Promise((r) => setTimeout(r, 30));
    // Their ACTIVE filter stands; the stored CLOSED one does not land on top of it.
    expect(JSON.parse(screen.getByTestId("params").textContent ?? "{}")["f.status"]).toBe("in:ACTIVE");
  });

  it("remembers nothing when there is no staffer yet", () => {
    render(<Harness anonymous />);
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    expect(localStorage.getItem(registerViewKey("7", "loans"))).toBeNull();
  });
});

describe("a register that does not own the URL", () => {
  /** Six of these live on `/staff/applications` at once; only a sole register may drive the URL. */
  function Shared({ reg = "credit" }: { reg?: string } = {}) {
    rq = useRegisterQuery({ ...OPTS, registerId: reg, url: false });
    return <span data-testid="params">{JSON.stringify(rq.queryParams)}</span>;
  }

  it("never calls replace, however much the view changes", () => {
    render(<Shared />);
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    act(() => rq.setSort("amount", "asc"));
    act(() => rq.setPage(3));
    expect(replace).not.toHaveBeenCalled();
  });

  it("ignores a view in the URL — that one belongs to whoever owns the page", () => {
    search = "f.status=in%3ACLOSED&sort=amount&dir=asc";
    render(<Shared />);
    expect(JSON.parse(screen.getByTestId("params").textContent ?? "{}")).toEqual({
      sort: "dueDate",
      dir: "desc",
    });
  });

  it("still remembers its own view, under its own register id", () => {
    localStorage.setItem(registerViewKey("7", "credit"), "f.status=in%3ACLOSED");
    localStorage.setItem(registerViewKey("7", "disbursement"), "f.status=in%3AACTIVE");
    render(<Shared reg="credit" />);
    // Its own key, not its neighbour's — which is what keeps six panels on one page apart.
    expect(JSON.parse(screen.getByTestId("params").textContent ?? "{}")["f.status"]).toBe("in:CLOSED");
  });
});

describe("queryParams", () => {
  it("carries filters, search and sort but never the page", () => {
    render(<Harness />);
    act(() => rq.setQ("ravi"));
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    act(() => rq.setPage(3));
    const params = JSON.parse(screen.getByTestId("params").textContent ?? "{}");
    expect(params).toEqual({ "f.status": "in:ACTIVE", q: "ravi", sort: "dueDate", dir: "desc" });
  });

  it("is byte-identical for two filter sets built in a different order", () => {
    render(<Harness />);
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    act(() => rq.setFilter("amount", { type: "num", min: 1 }));
    const first = screen.getByTestId("params").textContent;

    act(() => rq.clearAll());
    act(() => rq.setFilter("amount", { type: "num", min: 1 }));
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    // Same view, same cache entry — otherwise React Query holds two copies of one result.
    expect(screen.getByTestId("params").textContent).toBe(first);
  });
});

describe("toggleSort", () => {
  it("flips the direction on the same column and starts a new one descending", () => {
    render(<Harness />);
    act(() => rq.toggleSort("dueDate"));
    expect(screen.getByTestId("sort")).toHaveTextContent("dueDate:asc");
    act(() => rq.toggleSort("amount"));
    expect(screen.getByTestId("sort")).toHaveTextContent("amount:desc");
  });
});

describe("clearAll", () => {
  it("drops the search and the filters but keeps the sort", () => {
    render(<Harness />);
    act(() => rq.setQ("ravi"));
    act(() => rq.setSort("amount", "asc"));
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    act(() => rq.clearAll());
    expect(screen.getByTestId("active")).toHaveTextContent("0");
    expect(screen.getByTestId("sort")).toHaveTextContent("amount:asc");
  });
});
