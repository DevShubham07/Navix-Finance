/**
 * The server adapter's contract. Mocks the two fetchers and asserts on the calls they receive —
 * the requests are the behaviour; the hook's internals are not.
 */
import { render, act, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as React from "react";
import { useRegisterQuery, type RegisterQuery } from "@/lib/staff/register-query";
import { useServerRegister, type ServerRegister } from "@/lib/staff/use-server-register";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn() }),
  usePathname: () => "/staff/customers",
  useSearchParams: () => new URLSearchParams(""),
}));

const fetchPage = vi.fn();
const fetchFacet = vi.fn();

let rq: RegisterQuery;
let sr: ServerRegister<{ id: number }>;
let qc: QueryClient;

function Harness({ enabled }: { enabled?: boolean } = {}) {
  rq = useRegisterQuery({
    registerId: "customers",
    sortKeys: ["name"],
    filterKeys: ["status", "city"],
    defaults: { sort: { key: "name", dir: "asc" }, size: 25 },
  });
  sr = useServerRegister<{ id: number }>(rq, {
    key: ["t-customers"],
    fetchPage,
    fetchFacet,
    labels: { status: { ACTIVE: "Active" } },
    enabled,
  });
  return <span data-testid="total">{sr.total}</span>;
}

function mount(props: { enabled?: boolean } = {}) {
  qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <Harness {...props} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  fetchPage.mockReset();
  fetchFacet.mockReset();
  // 3 rows on this page out of 130 on the server.
  fetchPage.mockResolvedValue({ rows: [{ id: 1 }, { id: 2 }, { id: 3 }], total: 130 });
  fetchFacet.mockResolvedValue({ values: ["ACTIVE", "CLOSED"], truncated: false });
});

describe("paging", () => {
  it("takes total and pageCount from the server's count, not rows.length", async () => {
    mount();
    await waitFor(() => expect(sr.total).toBe(130));
    expect(sr.rows).toHaveLength(3);
    expect(sr.pageRows).toBe(sr.rows);
    expect(sr.pageCount).toBe(6); // ceil(130 / 25)
  });

  it("fires exactly ONE request for a filter change, and it asks for page 1", async () => {
    mount();
    await waitFor(() => expect(sr.total).toBe(130));
    act(() => rq.setPage(4));
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(expect.anything(), 4, 25));

    fetchPage.mockClear();
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    await waitFor(() => expect(fetchPage).toHaveBeenCalled());
    // Let any stray second request (old page, then page 1) show itself.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
    });

    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(fetchPage).toHaveBeenCalledWith(expect.objectContaining({ "f.status": "in:ACTIVE" }), 1, 25);
  });

  it("keys the cache on the filters, so two filter sets never share an entry", async () => {
    mount();
    await waitFor(() => expect(sr.total).toBe(130));
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2));
    act(() => rq.setFilter("status", { type: "in", values: ["CLOSED"] }));
    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(3));

    const keys = qc.getQueryCache().findAll({ queryKey: ["t-customers", "page"] }).map((q) => JSON.stringify(q.queryKey));
    expect(new Set(keys).size).toBe(3);
    expect(keys.some((k) => k.includes("in:ACTIVE"))).toBe(true);
    expect(keys.some((k) => k.includes("in:CLOSED"))).toBe(true);
  });

  it("keeps the previous rows on screen, flagged as placeholder, while the next view loads", async () => {
    mount();
    await waitFor(() => expect(sr.total).toBe(130));
    let release: (v: unknown) => void = () => {};
    fetchPage.mockReturnValueOnce(new Promise((r) => (release = r)));
    act(() => rq.setFilter("status", { type: "in", values: ["ACTIVE"] }));
    await waitFor(() => expect(sr.isPlaceholderData).toBe(true));
    expect(sr.rows).toHaveLength(3);
    await act(async () => release({ rows: [{ id: 9 }], total: 1 }));
    await waitFor(() => expect(sr.isPlaceholderData).toBe(false));
    expect(sr.total).toBe(1);
  });
});

describe("facets", () => {
  it("does not fetch on mount, and is safe to read for an unfetched column", async () => {
    mount();
    await waitFor(() => expect(sr.total).toBe(130));
    expect(sr.optionsFor("status")).toEqual([]);
    expect(sr.facetTruncated("status")).toBe(false);
    expect(fetchFacet).not.toHaveBeenCalled();
  });

  it("fetches on first request, without that column's own filter, and serves labels", async () => {
    mount();
    await waitFor(() => expect(sr.total).toBe(130));
    act(() => rq.setFilters({ status: { type: "in", values: ["ACTIVE"] }, city: { type: "in", values: ["Pune"] } }));
    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2));

    act(() => sr.requestFacet("status"));
    await waitFor(() => expect(sr.optionsFor("status")).toEqual(["ACTIVE", "CLOSED"]));

    expect(fetchFacet).toHaveBeenCalledTimes(1);
    const [col, sent] = fetchFacet.mock.calls[0];
    expect(col).toBe("status");
    expect(sent).not.toHaveProperty("f.status");
    expect(sent).toHaveProperty("f.city", "in:Pune");
    expect(sr.labelsFor("status").get("ACTIVE")).toBe("Active");
  });

  it("serves a second request for the same facet from cache", async () => {
    mount();
    await waitFor(() => expect(sr.total).toBe(130));
    act(() => sr.requestFacet("status"));
    await waitFor(() => expect(sr.optionsFor("status")).toHaveLength(2));
    act(() => sr.requestFacet("city"));
    await waitFor(() => expect(fetchFacet).toHaveBeenCalledTimes(2));
    act(() => sr.requestFacet("status"));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 30));
    });
    expect(fetchFacet).toHaveBeenCalledTimes(2);
    expect(sr.optionsFor("status")).toEqual(["ACTIVE", "CLOSED"]);
  });

  it("surfaces truncation so a page can fall back to a text filter", async () => {
    fetchFacet.mockResolvedValue({ values: ["a"], truncated: true });
    mount();
    await waitFor(() => expect(sr.total).toBe(130));
    act(() => sr.requestFacet("city"));
    await waitFor(() => expect(sr.facetTruncated("city")).toBe(true));
    expect(sr.facetTruncated("status")).toBe(false);
  });
});

describe("enabled", () => {
  /** A register behind a permission gate must not ask the server for a page before the session says
   *  who is signed in, or the admin registers fire a 403 on first paint. Without this the caller has
   *  to split the page into an outer gate and an inner component purely to mount the hooks late. */
  it("asks for nothing at all while false", async () => {
    mount({ enabled: false });
    await waitFor(() => expect(screen.getByTestId("total")).toHaveTextContent("0"));
    expect(fetchPage).not.toHaveBeenCalled();
  });

  it("does not fetch a facet either, even when a panel asks", async () => {
    mount({ enabled: false });
    act(() => sr.requestFacet("status"));
    await waitFor(() => expect(screen.getByTestId("total")).toHaveTextContent("0"));
    expect(fetchFacet).not.toHaveBeenCalled();
  });

  it("fetches once it is allowed", async () => {
    mount({ enabled: true });
    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(1));
  });
});
