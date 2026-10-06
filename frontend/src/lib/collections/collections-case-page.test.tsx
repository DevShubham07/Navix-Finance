/**
 * `/staff/collections/[loanId]` (Phase 2): the promise-to-pay date belongs to PROMISE_TO_PAY only
 * (with a non-blocking warning when it is empty), and the rupee boxes only submit a positive amount,
 * converted to paise.
 *
 * Lives under `src/lib/collections` beside `rupee-amount-input.ts`; the page is imported by path.
 */
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { collectionsApi, customersApi, type CaseDetailView } from "@/lib/api/applications";
import CollectionsCasePage from "@/app/staff/collections/[loanId]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/staff/collections/77",
  useSearchParams: () => new URLSearchParams(""),
  useParams: () => ({ loanId: "77" }),
}));

const ME = { id: "5", name: "Officer", role: "COLLECTION_EXECUTIVE" };

const CASE: CaseDetailView = {
  id: "9f0c1e2d-0000-0000-0000-000000000077",
  loanId: 77,
  assignedOfficerId: 5,
  assignedOfficerName: "Officer",
  createdAt: "2026-10-01T10:00:00Z",
  dpd: 4,
  bucket: "T0_T7",
  loan: null,
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CollectionsCasePage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session: ME })));
  vi.spyOn(collectionsApi, "caseByLoan").mockResolvedValue(CASE);
  vi.spyOn(collectionsApi, "listInteractions").mockResolvedValue([]);
  vi.spyOn(collectionsApi, "listCasePayments").mockResolvedValue([]);
  vi.spyOn(customersApi, "get").mockResolvedValue(null as never);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("CollectionsCasePage", () => {
  it("shows the promise-to-pay date only for PROMISE_TO_PAY, warning when it is empty", async () => {
    const log = vi.spyOn(collectionsApi, "logInteraction").mockResolvedValue({} as never);
    renderPage();

    const outcome = await screen.findByLabelText("Outcome");
    expect(screen.queryByLabelText("Promise-to-pay")).toBeNull();

    fireEvent.change(outcome, { target: { value: "PROMISE_TO_PAY" } });
    const date = screen.getByLabelText("Promise-to-pay");
    expect(screen.getByText(/No promise-to-pay date/)).toBeInTheDocument();

    fireEvent.change(date, { target: { value: "2026-10-12" } });
    expect(screen.queryByText(/No promise-to-pay date/)).toBeNull();

    // Switching away hides the date, and a hidden date is not sent.
    fireEvent.change(outcome, { target: { value: "NO_ANSWER" } });
    expect(screen.queryByLabelText("Promise-to-pay")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Log" }));
    await waitFor(() => expect(log).toHaveBeenCalledTimes(1));
    expect(log.mock.calls[0][1]).toEqual({
      type: "CALL",
      outcome: "NO_ANSWER",
      promiseToPayDate: undefined,
      proofRef: undefined,
    });
  });

  it("keeps Propose disabled until the settlement amount is a positive number, and sends paise", async () => {
    const propose = vi.spyOn(collectionsApi, "proposeSettlement").mockResolvedValue({
      settlementAmountPaise: 1_050,
    } as never);
    renderPage();

    const amount = await screen.findByLabelText("Settlement amount (₹)");
    expect(amount).toHaveAttribute("inputmode", "decimal");
    const button = screen.getByRole("button", { name: "Propose" });

    fireEvent.change(amount, { target: { value: "." } });
    expect(button).toBeDisabled();

    fireEvent.change(amount, { target: { value: "0" } });
    expect(button).toBeDisabled();

    fireEvent.change(amount, { target: { value: "10.5.09" } });
    expect(amount).toHaveValue("10.50");
    expect(button).toBeEnabled();

    fireEvent.click(button);
    await waitFor(() => expect(propose).toHaveBeenCalledWith(CASE.id, 1_050));
  });

  it("applies the same rule to the payment amount", async () => {
    const raise = vi.spyOn(collectionsApi, "raisePayment").mockResolvedValue({
      amountPaise: 250_075,
      status: "PENDING_ACCOUNTANT",
    } as never);
    renderPage();

    const amount = await screen.findByLabelText("Amount (₹)");
    expect(amount).toHaveAttribute("inputmode", "decimal");
    const button = screen.getByRole("button", { name: "Record payment" });
    expect(button).toBeDisabled();

    fireEvent.change(amount, { target: { value: "₹2,500.759" } });
    expect(amount).toHaveValue("2500.75");
    fireEvent.click(button);
    await waitFor(() => expect(raise).toHaveBeenCalledTimes(1));
    expect(raise.mock.calls[0][1]).toMatchObject({ kind: "PART_PAYMENT", amountPaise: 250_075 });
  });
});
