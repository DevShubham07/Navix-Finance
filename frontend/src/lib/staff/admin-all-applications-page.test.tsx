/**
 * Page-level checks for `/staff/admin/all-applications` (Phase 2): the ADMIN-only register waits for
 * the role before asking for data, does not retry a refusal, shows when each application entered
 * its stage, and offers one "Clear" for the search + completeness filter.
 *
 * Lives under `src/lib/staff` beside the helpers it exercises; the page itself is imported by path.
 */
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApplicationApiError, staffApi, type AdminApplicationView } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";
import AdminAllApplicationsPage from "@/app/staff/admin/all-applications/page";

function app(overrides: Partial<AdminApplicationView> = {}): AdminApplicationView {
  return {
    id: 1,
    customerId: 10,
    status: "DRAFT",
    amountRequestedPaise: null,
    eligibleLimitPaise: null,
    purpose: null,
    salaryCreditDay: null,
    assignedExecutiveId: null,
    loanId: null,
    hasProfile: true,
    fullName: null,
    pan: null,
    mobile: null,
    email: null,
    dob: null,
    address: null,
    employer: null,
    employmentStatus: null,
    monthlySalaryPaise: null,
    salaryBank: null,
    creditScore: null,
    starRating: null,
    recommendation: null,
    riskCategory: null,
    stepsCompleted: 0,
    stepsRequired: 5,
    agreementAccepted: true,
    complete: false,
    kycCapturedAt: null,
    bureauState: "NOT_FETCHED",
    ...overrides,
  };
}

const STAGE_AT = "2026-09-14T08:30:00Z";
const ROWS = [
  app({ id: 1, customerId: 10, fullName: "Asha Rao", complete: true, status: "SANCTIONED", currentStageEnteredAt: STAGE_AT }),
  app({ id: 2, customerId: 20, fullName: "Ravi Kumar", complete: false, status: "KYC_PENDING", currentStageEnteredAt: null }),
];

/** Answer the BFF's `/me` with `session` (null = signed out); nothing else should reach fetch. */
function mockSession(session: { id: string; name: string; role: string } | null) {
  // Admin pages are the Admin WORKING role's (the default is Credit Head).
  if (session?.role === "ADMIN") localStorage.setItem(`navix-staff-working-role:${session.id}`, "ADMIN");
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(JSON.stringify({ session })));
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AdminAllApplicationsPage />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("AdminAllApplicationsPage", () => {
  it("never asks a non-admin's register for data", async () => {
    mockSession({ id: "7", name: "Head", role: "CREDIT_HEAD" });
    const list = vi.spyOn(staffApi, "listAllApplications").mockResolvedValue(ROWS);
    renderPage();

    expect(await screen.findByText("Admin access only.")).toBeInTheDocument();
    expect(list).not.toHaveBeenCalled();
  });

  it("does not fire for a signed-out session, and says so instead of claiming no access", async () => {
    mockSession(null);
    const list = vi.spyOn(staffApi, "listAllApplications").mockResolvedValue(ROWS);
    renderPage();

    expect(await screen.findByText("Couldn't confirm your staff session.")).toBeInTheDocument();
    expect(screen.queryByText("Admin access only.")).not.toBeInTheDocument();
    expect(list).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: /Refresh/ })).toBeDisabled();
  });

  it("asks once for an admin and does not retry a 4xx", async () => {
    mockSession({ id: "1", name: "Admin", role: "ADMIN" });
    const list = vi
      .spyOn(staffApi, "listAllApplications")
      .mockRejectedValue(new ApplicationApiError("Not allowed", "FORBIDDEN_ROLE", 403));
    // A client whose default WOULD retry, so the page's own `retry` is what is under test.
    const client = new QueryClient({ defaultOptions: { queries: { retry: 3 } } });
    render(
      <QueryClientProvider client={client}>
        <AdminAllApplicationsPage />
      </QueryClientProvider>,
    );

    expect(await screen.findByText(/Not allowed/)).toBeInTheDocument();
    expect(list).toHaveBeenCalledTimes(1);
  });

  it("shows when each application entered its stage", async () => {
    mockSession({ id: "1", name: "Admin", role: "ADMIN" });
    vi.spyOn(staffApi, "listAllApplications").mockResolvedValue(ROWS);
    renderPage();

    await screen.findByText("Asha Rao");
    expect(screen.getByRole("columnheader", { name: "Stage since" })).toBeInTheDocument();
    const asha = screen.getByText("Asha Rao").closest("tr")!;
    expect(within(asha).getByText(formatDateTime(STAGE_AT))).toBeInTheDocument();
    const ravi = screen.getByText("Ravi Kumar").closest("tr")!;
    // Ravi's row has no stage timestamp: an em dash, never an invented date.
    expect(within(ravi).getAllByText("—").length).toBeGreaterThan(0);
    expect(screen.getByRole("table", { name: "All applications" })).toBeInTheDocument();
  });

  it("offers Clear only while a filter is active, and Clear restores the whole register", async () => {
    mockSession({ id: "1", name: "Admin", role: "ADMIN" });
    vi.spyOn(staffApi, "listAllApplications").mockResolvedValue(ROWS);
    renderPage();

    await screen.findByText("Asha Rao");
    expect(screen.getByText("2 of 2")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Clear" })).not.toBeInTheDocument();

    const search = screen.getByRole("textbox", { name: "Search applications" });
    fireEvent.change(search, { target: { value: "ravi" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));

    await waitFor(() => expect(screen.getByText("1 of 2")).toBeInTheDocument());
    expect(screen.queryByText("Asha Rao")).not.toBeInTheDocument();

    fireEvent.change(screen.getByRole("combobox", { name: "Completeness" }), { target: { value: "INCOMPLETE" } });
    expect(screen.getByText("1 of 2")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Clear" }));

    await waitFor(() => expect(screen.getByText("2 of 2")).toBeInTheDocument());
    expect(screen.getByText("Asha Rao")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Clear" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Search applications" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Completeness" })).toHaveValue("ALL");
    // The chip removed itself; focus lands on the search box, not on <body>.
    expect(screen.getByRole("textbox", { name: "Search applications" })).toHaveFocus();
  });
});
