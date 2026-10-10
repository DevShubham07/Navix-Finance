/**
 * Customer 360 tab polish (components/staff/customer-tabs.tsx, via the exported CustomerTabBody):
 *  - Audit Logs: filter chips derived from the event types present, client-side, "All" by default;
 *  - Loan Applications: the one-line exposure summary computed from the detail payload alone.
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import {
  customersApi,
  type ActivityEntry,
  type ApplicationView,
  type CustomerDetail,
  type LoanView,
  type PaymentView,
} from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

const APP = { id: 318, status: "ACTIVE", amountRequestedPaise: 1_000_000 } as unknown as ApplicationView;

function detail(overrides: Partial<CustomerDetail> = {}): CustomerDetail {
  return {
    customerId: 42,
    profile: null,
    applications: [APP],
    loans: [],
    payments: [],
    ...overrides,
  };
}

function renderTab(tab: string, d: CustomerDetail) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CustomerTabBody tab={tab} detail={d} customerId={42} />
    </QueryClientProvider>,
  );
}

const ev = (type: ActivityEntry["type"], title: string, applicationId: number | null = 318): ActivityEntry => ({
  type,
  applicationId,
  title,
  detail: null,
  actor: "ADMIN",
  at: "2026-10-05T05:30:00Z",
});

describe("Audit Logs filter chips", () => {
  it("offers All plus one chip per type present, and filters client-side", async () => {
    vi.spyOn(customersApi, "documents").mockResolvedValue([]);
    const activity = vi
      .spyOn(customersApi, "activity")
      .mockResolvedValue([
        ev("LIFECYCLE", "Sanction"),
        ev("LIFECYCLE", "Disburse"),
        ev("PROFILE", "Updated Monthly salary"),
        ev("REMARK", "Remark", null),
      ]);
    renderTab("activity", detail());

    const group = await screen.findByRole("group", { name: "Filter activity by type" });
    const chips = within(group).getAllByRole("button").map((b) => b.textContent);
    expect(chips).toEqual(["All (4)", "Lifecycle (2)", "Profile edit (1)", "Remark (1)"]);
    expect(within(group).getByRole("button", { name: "All (4)" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("Sanction")).toBeInTheDocument();

    fireEvent.click(within(group).getByRole("button", { name: "Lifecycle (2)" }));
    expect(screen.getByText("Sanction")).toBeInTheDocument();
    expect(screen.getByText("Disburse")).toBeInTheDocument();
    expect(screen.queryByText("Updated Monthly salary")).not.toBeInTheDocument();
    expect(screen.queryByText("Not tied to an application")).not.toBeInTheDocument();

    fireEvent.click(within(group).getByRole("button", { name: "Remark (1)" }));
    expect(screen.queryByText("Sanction")).not.toBeInTheDocument();
    expect(screen.getByText("No remark events for this application.")).toBeInTheDocument();
    expect(screen.getByText("Not tied to an application")).toBeInTheDocument();

    fireEvent.click(within(group).getByRole("button", { name: "All (4)" }));
    expect(screen.getByText("Updated Monthly salary")).toBeInTheDocument();
    // Client-side only: one fetch, however many chips were clicked.
    expect(activity).toHaveBeenCalledTimes(1);
  });

  it("shows no chip row when only one type is present", async () => {
    vi.spyOn(customersApi, "documents").mockResolvedValue([]);
    vi.spyOn(customersApi, "activity").mockResolvedValue([ev("LIFECYCLE", "Sanction")]);
    renderTab("activity", detail());
    expect(await screen.findByText("Sanction")).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Filter activity by type" })).not.toBeInTheDocument();
  });
});

const loan = (id: number, principalPaise: number, status = "CLOSED") =>
  ({
    id,
    customerId: 42,
    principalPaise,
    processingFeePaise: 0,
    gstPaise: 0,
    netDisbursedPaise: principalPaise,
    dailyInterestRate: 0.01,
    disbursedOn: "2026-09-01",
    dueDate: "2026-09-30",
    totalRepayablePaise: principalPaise,
    outstandingPaise: 0,
    status,
    disbursalTxnRef: null,
    closedOn: null,
  }) as LoanView;

const pay = (id: number, status: PaymentView["status"], paidOn: string) =>
  ({ id, loanId: 1, amountPaise: 100_000, method: "UPI", status, paidOn, partial: false }) as PaymentView;

describe("Loans exposure summary", () => {
  it("reads total principal, outstanding and last verified payment off the payload", async () => {
    vi.spyOn(customersApi, "documents").mockResolvedValue([]);
    vi.spyOn(customersApi, "mobileMatches").mockResolvedValue({ bureau: null, checked: [], matches: [] });
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    renderTab(
      "loan",
      detail({
        loans: [loan(2, 2_000_000, "ACTIVE"), loan(1, 1_000_000)],
        payments: [pay(1, "VERIFIED", "2026-09-28"), pay(2, "PENDING_VERIFICATION", "2026-10-04")],
        outstandingByLoanId: {
          "2": { loanId: 2, asOf: "2026-10-06", outstandingPaise: 2_100_000 },
          "1": { loanId: 1, asOf: "2026-10-06", outstandingPaise: 0 },
        },
      }),
    );
    const line = screen.getByText(/Total principal/).closest("p")!;
    expect(line).toHaveTextContent("Total principal ₹30,000");
    expect(line).toHaveTextContent("· Outstanding ₹21,000");
    // ICU spells the en-IN short month "Sep" or "Sept" depending on its version.
    expect(line).toHaveTextContent(/· Last verified payment 28 Sept? 2026/);
    // Nothing here is fetched for the summary (documents is the manual-review badge's own read).
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("omits outstanding and last payment when the payload does not carry them", () => {
    vi.spyOn(customersApi, "documents").mockResolvedValue([]);
    renderTab("loan", detail({ loans: [loan(1, 1_000_000)], payments: [] }));
    const line = screen.getByText(/Total principal/).closest("p")!;
    expect(line).toHaveTextContent("Total principal ₹10,000");
    expect(line).not.toHaveTextContent("Outstanding");
    expect(line).not.toHaveTextContent("Last verified payment");
  });

  it("lists applications only — loans and payments belong to the Loan card / Repayment tab", () => {
    vi.spyOn(customersApi, "documents").mockResolvedValue([]);
    renderTab("loan", detail({ loans: [loan(1, 1_000_000)], payments: [pay(1, "VERIFIED", "2026-09-28")] }));
    expect(screen.getByText("Applications (1)")).toBeInTheDocument();
    expect(screen.queryByText(/^Loans \(/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Payments \(/)).not.toBeInTheDocument();
  });
});

describe("Customer tab compliance", () => {
  it("shows the terms / PEP consent trail", () => {
    vi.spyOn(customersApi, "documents").mockResolvedValue([]);
    renderTab(
      "customer",
      detail({ profile: { termsVersion: "v3" } as unknown as CustomerDetail["profile"] }),
    );
    expect(screen.getByText("Compliance")).toBeInTheDocument();
    expect(screen.getByText("v3")).toBeInTheDocument();
  });
});
