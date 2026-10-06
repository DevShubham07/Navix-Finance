/**
 * Page-level checks for `/staff/my-decisions` (Phase 2): client-side search, the in-place
 * application dialog, URL write-back, and — the Phase 0.1 defect class — rows carried across a
 * period change but never across a change of person.
 *
 * Lives under `src/lib/staff` beside the helpers it exercises; the page itself is imported by path.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { staffApi, type DecisionView } from "@/lib/api/applications";
// `vi.mock` calls below are hoisted above every import, so the page sees the mocked modules.
import MyDecisionsPage from "@/app/staff/my-decisions/page";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/my-decisions",
  useSearchParams: () => new URLSearchParams(window.location.search),
}));

// The real dialog fetches a whole file; here it only has to prove which application was opened.
vi.mock("@/components/staff/application-detail-dialog", () => ({
  ApplicationDetailDialog: ({ applicationId }: { applicationId: number | null }) =>
    applicationId == null ? null : <div role="dialog">Application {applicationId}</div>,
}));

function decision(overrides: Partial<DecisionView> = {}): DecisionView {
  return {
    applicationId: 318,
    customerId: 42,
    customerName: "Asha Verma",
    pan: "ABCDE1234F",
    action: "SANCTION",
    fromStatus: "CREDIT_EXEC_PENDING",
    toStatus: "SANCTIONED",
    at: "2026-10-01T05:30:00Z",
    amountPaise: 1_000_000,
    salaryCreditDay: 30,
    repaymentDate: "2026-10-30",
    assigneeId: null,
    assigneeName: null,
    txnRef: null,
    remark: "Salary verified against the slip",
    notes: "amountPaise=1000000 salaryCreditDay=30 Salary verified against the slip",
    ...overrides,
  };
}

const MINE = [
  decision(),
  decision({ applicationId: 401, customerId: 77, customerName: "Bilal Khan", pan: "PQRSX9876Z", at: "2026-10-02T05:30:00Z" }),
];

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MyDecisionsPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  replace.mockReset();
  window.history.replaceState({}, "", "/staff/my-decisions");
  // `useStaffMe` asks the BFF who is signed in: a Credit Head, so the person picker renders.
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ session: { id: 5, name: "Head", role: "CREDIT_HEAD" } })),
  );
  vi.spyOn(staffApi, "inspectableStaff").mockResolvedValue([
    { id: 5, name: "Head", role: "CREDIT_HEAD", active: true },
    { id: 7, name: "Ravi", role: "CREDIT_EXECUTIVE", active: true },
  ]);
  vi.spyOn(staffApi, "performance").mockResolvedValue({ rows: [], daily: [], callTrackingSince: "2026-07-01" });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("MyDecisionsPage", () => {
  it("filters the loaded rows by customer name, and says so when nothing matches", async () => {
    vi.spyOn(staffApi, "decisions").mockResolvedValue(MINE);
    renderPage();
    await screen.findByRole("button", { name: "Open application #318" });

    const box = screen.getByRole("textbox", { name: /Search decisions/ });
    fireEvent.change(box, { target: { value: "bilal" } });
    fireEvent.submit(box.closest("form")!);
    expect(await screen.findByRole("button", { name: "Open application #401" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Open application #318" })).not.toBeInTheDocument();

    fireEvent.change(box, { target: { value: "nobody" } });
    fireEvent.submit(box.closest("form")!);
    expect(await screen.findByText("No decisions match “nobody”.")).toBeInTheDocument();
  });

  it("opens the application dialog in place from the application id", async () => {
    vi.spyOn(staffApi, "decisions").mockResolvedValue(MINE);
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Open application #401" }));
    expect(within(screen.getByRole("dialog")).getByText("Application 401")).toBeInTheDocument();
  });

  it("puts the raw audit notes behind a keyboard-reachable info button", async () => {
    vi.spyOn(staffApi, "decisions").mockResolvedValue([decision()]);
    renderPage();

    const info = await screen.findByRole("button", { name: "Full remark and audit notes" });
    fireEvent.click(info);
    expect(await screen.findByRole("tooltip")).toHaveTextContent("amountPaise=1000000");
  });

  it("keeps rows across a period change but clears them when the person changes", async () => {
    const decisions = vi.spyOn(staffApi, "decisions").mockImplementation(async (staffId) => {
      if (staffId == null) return MINE;
      return new Promise<DecisionView[]>(() => {}); // Ravi's history never arrives in this test
    });
    renderPage();
    await screen.findByRole("button", { name: "Open application #318" });

    // Period change, same person: still pending, the previous rows stay on screen.
    decisions.mockImplementation(() => new Promise<DecisionView[]>(() => {}));
    fireEvent.click(screen.getByRole("button", { name: "Today" }));
    await waitFor(() => expect(decisions).toHaveBeenCalledTimes(2));
    expect(screen.getByRole("button", { name: "Open application #318" })).toBeInTheDocument();

    // Person change: nothing of the caller's may sit under Ravi's name.
    fireEvent.change(await screen.findByRole("combobox", { name: "Whose decisions to show" }), {
      target: { value: "7" },
    });
    await waitFor(() => expect(decisions).toHaveBeenLastCalledWith(7, expect.any(String), expect.any(String)));
    expect(screen.queryByRole("button", { name: "Open application #318" })).not.toBeInTheDocument();
  });

  it("writes the person and window back to the URL with replace", async () => {
    window.history.replaceState({}, "", "/staff/my-decisions?staffId=7&from=2026-09-01&to=2026-09-30");
    vi.spyOn(staffApi, "decisions").mockResolvedValue(MINE);
    renderPage();

    const picker = await screen.findByRole("combobox", { name: "Whose decisions to show" });
    // The seeded URL already describes this state: nothing to write.
    expect(replace).not.toHaveBeenCalled();

    fireEvent.change(picker, { target: { value: "" } });
    await waitFor(() =>
      expect(replace).toHaveBeenLastCalledWith("/staff/my-decisions?from=2026-09-01&to=2026-09-30", { scroll: false }),
    );
  });
});
