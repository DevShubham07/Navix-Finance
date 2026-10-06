/**
 * `LoanDetailDialog`'s optional `customerId` / `applicationId` hints (Phase 2):
 *  - with a hint the customer roll-up starts alongside the loan read instead of waiting for it;
 *  - without one (a `?open=` deep link, loan history) it still waits on the loan, as before;
 *  - once the loan loads its own customer id wins, so a wrong hint can only decide what is fetched
 *    first, never whose customer the dialog ends up showing.
 *
 * Lives under `src/lib/loans` beside the register's other tests; the dialog is imported by path.
 */
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import {
  collectionsApi,
  customersApi,
  staffApi,
  type CustomerDetail,
  type LoanView,
} from "@/lib/api/applications";
import { LoanDetailDialog } from "@/components/staff/loan-detail-dialog";

const never = <T,>() => new Promise<T>(() => {});

function loanView(overrides: Partial<LoanView> = {}): LoanView {
  return {
    id: 7,
    customerId: 9,
    principalPaise: 1_000_000,
    processingFeePaise: 100_000,
    gstPaise: 18_000,
    netDisbursedPaise: 882_000,
    dailyInterestRate: 0.01,
    disbursedOn: "2026-09-02",
    dueDate: "2026-09-30",
    totalRepayablePaise: 1_280_000,
    outstandingPaise: 1_280_000,
    status: "ACTIVE",
    disbursalTxnRef: null,
    closedOn: null,
    ...overrides,
  };
}

function renderDialog(props: { customerId?: number | null; applicationId?: number | null } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <LoanDetailDialog loanId={7} onClose={() => {}} {...props} />
    </QueryClientProvider>,
  );
}

let customerGet: MockInstance<typeof customersApi.get>;

beforeEach(() => {
  vi.spyOn(staffApi, "outstanding").mockImplementation(() => never());
  vi.spyOn(collectionsApi, "caseByLoan").mockResolvedValue(null);
  customerGet = vi.spyOn(customersApi, "get").mockImplementation(() => never<CustomerDetail>());
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("LoanDetailDialog customer/application hints", () => {
  it("starts the customer read from the hint while the loan is still loading", async () => {
    vi.spyOn(staffApi, "loan").mockImplementation(() => never<LoanView>());
    renderDialog({ customerId: 9, applicationId: 31 });

    await waitFor(() => expect(customerGet).toHaveBeenCalledWith(9));
    expect(screen.getByText("Customer #9")).toBeInTheDocument();
  });

  it("without a hint, waits for the loan before reading the customer", async () => {
    let resolveLoan: (v: LoanView) => void = () => {};
    vi.spyOn(staffApi, "loan").mockImplementation(
      () => new Promise<LoanView>((resolve) => (resolveLoan = resolve)),
    );
    renderDialog();

    await waitFor(() => expect(staffApi.loan).toHaveBeenCalledWith(7));
    expect(customerGet).not.toHaveBeenCalled();

    resolveLoan(loanView());
    await waitFor(() => expect(customerGet).toHaveBeenCalledWith(9));
  });

  it("lets the loan's own customer id win over a mismatched hint", async () => {
    vi.spyOn(staffApi, "loan").mockResolvedValue(loanView({ customerId: 9 }));
    renderDialog({ customerId: 8 });

    await waitFor(() => expect(customerGet).toHaveBeenCalledWith(9));
    expect(await screen.findByText("Customer #9")).toBeInTheDocument();
    expect(screen.queryByText("Customer #8")).not.toBeInTheDocument();
  });
});
