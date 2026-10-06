/**
 * Page-level checks for `/staff/leads` (Phase 2): the list waits for the role gate, the filtered
 * empty state, keyboard row selection, the explicit "Clear rating", the one Save in the side panel,
 * and the rupee boxes on the new-lead form.
 *
 * Lives under `src/lib/leads` beside the helpers it exercises; the page itself is imported by path.
 */
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { leadsApi, type LeadPage, type LeadView } from "@/lib/api/applications";
import { toast } from "@/components/ui";
// `vi.mock` calls below are hoisted above every import, so the page sees the mocked modules.
import StaffLeadsPage from "@/app/staff/leads/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/leads",
  useSearchParams: () => new URLSearchParams(),
}));

function lead(overrides: Partial<LeadView> = {}): LeadView {
  return {
    id: 11,
    name: "Asha Verma",
    mobile: "9876543210",
    email: null,
    city: "Pune",
    employer: null,
    monthlySalaryPaise: null,
    loanAmountInterestedPaise: null,
    source: "DSA",
    sourceDetail: null,
    callStatus: "NOT_CALLED",
    qualityRating: null,
    notes: null,
    remarks: null,
    createdByStaffId: 5,
    createdByStaffName: "Tara",
    createdAt: "2026-10-01T05:30:00Z",
    updatedAt: null,
    leadOutcome: "NEW",
    dsaNote: null,
    ...overrides,
  };
}

function pageOf(rows: LeadView[]): LeadPage {
  return { rows, page: 1, size: 25, total: rows.length };
}

function signInAs(role: string) {
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ session: { id: 5, name: "Tara", role } })),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffLeadsPage />
    </QueryClientProvider>,
  );
}

/** The side panel's "Call status" select (the first one is the list filter). */
function panelCallStatus() {
  return screen.getAllByLabelText("Call status")[1] as HTMLSelectElement;
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe("list query and the role gate", () => {
  it("does not ask for leads when the staffer lacks leads:manage", async () => {
    signInAs("ACCOUNTANT");
    const list = vi.spyOn(leadsApi, "list").mockResolvedValue(pageOf([]));
    renderPage();
    expect(await screen.findByText("Telecaller or Admin access required.")).toBeInTheDocument();
    expect(list).not.toHaveBeenCalled();
  });

  it("asks for leads once the role resolves to one that holds leads:manage", async () => {
    signInAs("TELECALLER");
    const list = vi.spyOn(leadsApi, "list").mockResolvedValue(pageOf([lead()]));
    renderPage();
    expect(await screen.findByText("Asha Verma")).toBeInTheDocument();
    expect(list).toHaveBeenCalledTimes(1);
  });
});

describe("empty states", () => {
  it("says nothing exists only when no search or filter is applied", async () => {
    signInAs("TELECALLER");
    vi.spyOn(leadsApi, "list").mockResolvedValue(pageOf([]));
    renderPage();
    expect(await screen.findByText("No leads yet — add one above.")).toBeInTheDocument();
  });

  it("says the filter matched nothing, and clears it on request", async () => {
    signInAs("TELECALLER");
    const list = vi.spyOn(leadsApi, "list").mockResolvedValue(pageOf([]));
    renderPage();
    await screen.findByText("No leads yet — add one above.");

    fireEvent.change(screen.getAllByLabelText("Call status")[0], { target: { value: "CALLBACK" } });
    expect(await screen.findByText("No leads match your search or filter")).toBeInTheDocument();
    expect(screen.queryByText("No leads yet — add one above.")).not.toBeInTheDocument();
    // Reset in the change handler: the first request for the new filter is already on page 1.
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ callStatus: "CALLBACK", page: 1 }));

    fireEvent.click(screen.getByRole("button", { name: "Clear search and filter" }));
    expect(await screen.findByText("No leads yet — add one above.")).toBeInTheDocument();
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ callStatus: undefined, q: undefined }));
  });
});

describe("call-status filter", () => {
  it("never asks for the new filter at the old page", async () => {
    signInAs("TELECALLER");
    const rows = Array.from({ length: 25 }, (_, i) => lead({ id: 100 + i, name: `Lead ${i}` }));
    const list = vi.spyOn(leadsApi, "list").mockResolvedValue({ rows, page: 1, size: 25, total: 60 });
    renderPage();
    await screen.findByText("Lead 0");

    fireEvent.click(screen.getByRole("button", { name: /Next/ }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 })));

    fireEvent.change(screen.getAllByLabelText("Call status")[0], { target: { value: "CALLBACK" } });
    await waitFor(() =>
      expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ callStatus: "CALLBACK", page: 1 })),
    );
    const stale = list.mock.calls.filter(([p]) => p?.callStatus === "CALLBACK" && p.page !== 1);
    expect(stale).toEqual([]);
  });
});

describe("row selection", () => {
  it("selects a row from the keyboard with Enter or Space", async () => {
    signInAs("TELECALLER");
    vi.spyOn(leadsApi, "list").mockResolvedValue(
      pageOf([lead(), lead({ id: 12, name: "Bilal Khan", mobile: "9123456780" })]),
    );
    renderPage();
    const row = (await screen.findByText("Asha Verma")).closest("tr")!;
    expect(row).toHaveAttribute("tabindex", "0");

    fireEvent.keyDown(row, { key: "Enter" });
    expect(row).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("heading", { name: "Asha Verma" })).toBeInTheDocument();

    const other = screen.getByText("Bilal Khan").closest("tr")!;
    fireEvent.keyDown(other, { key: " " });
    expect(other).toHaveAttribute("aria-current", "true");
    expect(row).not.toHaveAttribute("aria-current");
  });
});

describe("disposition panel", () => {
  async function openPanel(view: LeadView = lead()) {
    signInAs("TELECALLER");
    vi.spyOn(leadsApi, "list").mockResolvedValue(pageOf([view]));
    renderPage();
    fireEvent.click(await screen.findByText(view.name));
    await screen.findByRole("heading", { name: view.name });
  }

  it("marks the chosen star pressed, keeps it on a second click, and clears only on request", async () => {
    await openPanel();
    const three = screen.getByRole("button", { name: "3 stars" });
    fireEvent.click(three);
    expect(three).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "2 stars" })).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(three);
    expect(three).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(screen.getByRole("button", { name: "Clear rating" }));
    expect(three).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "Clear rating" })).toBeDisabled();
  });

  it("sends only the disposition when the outcome and note are unchanged", async () => {
    await openPanel(lead({ leadOutcome: "OUTREACHED", dsaNote: "call back" }));
    const disposition = vi.spyOn(leadsApi, "disposition").mockResolvedValue(lead());
    const setOutcome = vi.spyOn(leadsApi, "setOutcome").mockResolvedValue(lead());
    const success = vi.spyOn(toast, "success");

    fireEvent.change(panelCallStatus(), { target: { value: "CALLBACK" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(success).toHaveBeenCalledWith("Disposition saved"));
    expect(disposition).toHaveBeenCalledWith(11, { callStatus: "CALLBACK", qualityRating: null, remarks: undefined });
    expect(setOutcome).not.toHaveBeenCalled();
    expect(success).toHaveBeenCalledTimes(1);
  });

  it("sends the outcome PUT with just the changed field, and one toast for both", async () => {
    await openPanel();
    const disposition = vi.spyOn(leadsApi, "disposition").mockResolvedValue(lead());
    const setOutcome = vi.spyOn(leadsApi, "setOutcome").mockResolvedValue(lead());
    const success = vi.spyOn(toast, "success");

    fireEvent.change(screen.getByLabelText("Note for the DSA"), { target: { value: "  rang twice " } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    // Names only what the requests carried: the outcome was never sent, so it is not "saved".
    await waitFor(() => expect(success).toHaveBeenCalledWith("Disposition & DSA note saved"));
    expect(disposition).toHaveBeenCalledTimes(1);
    expect(setOutcome).toHaveBeenCalledWith(11, { dsaNote: "rang twice" });
    expect(success).toHaveBeenCalledTimes(1);
  });

  it("toasts a failure when the lead leaves the filtered list before its error can show", async () => {
    signInAs("TELECALLER");
    const view = lead();
    const list = vi.spyOn(leadsApi, "list").mockResolvedValue(pageOf([view]));
    renderPage();
    fireEvent.change(screen.getAllByLabelText("Call status")[0], { target: { value: "NOT_CALLED" } });
    fireEvent.click(await screen.findByText(view.name));
    await screen.findByRole("heading", { name: view.name });

    vi.spyOn(leadsApi, "disposition").mockResolvedValue(lead({ callStatus: "CALLED" }));
    vi.spyOn(leadsApi, "setOutcome").mockRejectedValue(new Error("Outcome down"));
    const error = vi.spyOn(toast, "error");
    // The refetch after the Save no longer matches NOT_CALLED, so the panel unmounts.
    list.mockResolvedValue(pageOf([]));

    fireEvent.change(panelCallStatus(), { target: { value: "CALLED" } });
    fireEvent.change(screen.getByLabelText("Set outcome"), { target: { value: "OUTREACHED" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(error).toHaveBeenCalledWith("Asha Verma: outcome & note not saved — Outcome down"),
    );
    expect(screen.queryByRole("heading", { name: view.name })).not.toBeInTheDocument();
    expect(error).toHaveBeenCalledTimes(1);
  });

  it("toasts a failure that lands after another lead was picked mid-Save, not on the new lead", async () => {
    signInAs("TELECALLER");
    vi.spyOn(leadsApi, "list").mockResolvedValue(
      pageOf([lead(), lead({ id: 12, name: "Bilal Khan", mobile: "9123456780" })]),
    );
    renderPage();
    fireEvent.click(await screen.findByText("Asha Verma"));
    await screen.findByRole("heading", { name: "Asha Verma" });

    let fail: (e: Error) => void = () => {};
    vi.spyOn(leadsApi, "disposition").mockImplementation(
      () => new Promise<LeadView>((_, reject) => (fail = reject)),
    );
    const error = vi.spyOn(toast, "error");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    fireEvent.click(screen.getByText("Bilal Khan"));
    await screen.findByRole("heading", { name: "Bilal Khan" });
    fail(new Error("Disposition down"));

    await waitFor(() =>
      expect(error).toHaveBeenCalledWith("Asha Verma: call status, rating & remarks not saved — Disposition down"),
    );
    expect(error).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/not saved/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
  });

  it("raises nothing when a lead whose Save succeeded is left", async () => {
    signInAs("TELECALLER");
    vi.spyOn(leadsApi, "list").mockResolvedValue(
      pageOf([lead(), lead({ id: 12, name: "Bilal Khan", mobile: "9123456780" })]),
    );
    renderPage();
    fireEvent.click(await screen.findByText("Asha Verma"));
    await screen.findByRole("heading", { name: "Asha Verma" });
    vi.spyOn(leadsApi, "disposition").mockResolvedValue(lead());
    const success = vi.spyOn(toast, "success");
    const error = vi.spyOn(toast, "error");

    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(success).toHaveBeenCalledWith("Disposition saved"));
    await waitFor(() => expect(screen.getByRole("button", { name: "Save" })).toBeEnabled());

    fireEvent.click(screen.getByText("Bilal Khan"));
    await screen.findByRole("heading", { name: "Bilal Khan" });
    expect(error).not.toHaveBeenCalled();
  });

  it("keeps both errors visible when both requests fail", async () => {
    await openPanel();
    vi.spyOn(leadsApi, "disposition").mockRejectedValue(new Error("Disposition down"));
    vi.spyOn(leadsApi, "setOutcome").mockRejectedValue(new Error("Outcome down"));
    const success = vi.spyOn(toast, "success");

    fireEvent.change(screen.getByLabelText("Set outcome"), { target: { value: "REJECTED" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText(/Call status, rating & remarks not saved: Disposition down/)).toBeInTheDocument();
    expect(await screen.findByText(/Outcome & note not saved: Outcome down/)).toBeInTheDocument();
    expect(success).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByRole("button", { name: "Save" })).toBeEnabled());
  });
});

describe("new-lead rupee boxes", () => {
  async function openForm() {
    signInAs("TELECALLER");
    vi.spyOn(leadsApi, "list").mockResolvedValue(pageOf([]));
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: /New lead/ }));
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: "Chitra Rao" } });
    fireEvent.change(screen.getByLabelText(/^Mobile/), { target: { value: "9988776655" } });
  }

  it("accepts Indian grouping and sends integer paise", async () => {
    await openForm();
    const create = vi.spyOn(leadsApi, "create").mockResolvedValue(lead());
    fireEvent.change(screen.getByLabelText("Monthly salary (₹)"), { target: { value: "2,50,000" } });
    fireEvent.change(screen.getByLabelText("Loan amount interested (₹)"), { target: { value: "25,000" } });
    fireEvent.click(screen.getByRole("button", { name: "Save lead" }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({ monthlySalaryPaise: 25_000_000, loanAmountInterestedPaise: 2_500_000 }),
    );
  });

  it("shows an error for a value that is not a positive amount and blocks Save", async () => {
    await openForm();
    const create = vi.spyOn(leadsApi, "create").mockResolvedValue(lead());
    const salary = screen.getByLabelText("Monthly salary (₹)");
    fireEvent.focus(salary);
    fireEvent.change(salary, { target: { value: "-500" } });
    expect(screen.getByRole("button", { name: "Save lead" })).toBeDisabled();

    fireEvent.blur(salary);
    expect(screen.getByText("Enter a rupee amount above 0, e.g. 25,000")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Save lead" }));
    expect(create).not.toHaveBeenCalled();

    fireEvent.change(salary, { target: { value: "" } });
    expect(screen.getByRole("button", { name: "Save lead" })).toBeEnabled();
  });
});
