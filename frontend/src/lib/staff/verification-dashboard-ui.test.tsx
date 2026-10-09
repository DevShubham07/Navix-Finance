import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  ApplicationApiError,
  VerificationRetryTimeoutError,
  staffApi,
  type ApplicationView,
  type StepResult,
  type VerificationOverview,
} from "@/lib/api/applications";
import { VerificationChecksPanel } from "@/components/staff/verification-checks";
import VerificationsDashboardPage from "@/app/staff/verifications/page";

/**
 * Lives under lib/staff beside the helpers it exercises (verification-dashboard.ts); it renders the
 * `/staff/verifications` page and the shared checks panel from `components/staff/verification-checks.tsx`.
 */

function renderWithClient(ui: React.ReactElement) {
  // staleTime: Infinity so the seeded session is never refetched over the network.
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  // ADMIN is the one role holding both kyc:approve and verification:retry.
  qc.setQueryData(["staff-me"], { id: "1", name: "Admin", role: "ADMIN", realRole: "ADMIN" });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

function bureau(checkedAt: string): StepResult {
  return {
    checkType: "BUREAU",
    status: "REVIEW",
    message: "No hit",
    derived: {},
    provider: "DIGITAP",
    checkedAt,
  };
}

function stubPanel(rows: () => StepResult[]) {
  vi.spyOn(staffApi, "verifications").mockImplementation(async () => rows());
  vi.spyOn(staffApi, "verificationProgress").mockResolvedValue({
    required: 4,
    completed: 1,
    failed: 0,
    pending: 3,
    percent: 25,
  });
  vi.spyOn(staffApi, "get").mockResolvedValue({ id: 42, status: "KYC_PENDING" } as ApplicationView);
}

/** Real rows render before the NOT_RUN placeholders, so with one real row it is the first card. */
async function bureauRetryButton() {
  const buttons = await screen.findAllByRole("button", { name: "Retry API" });
  return buttons[0];
}

async function sendRetry() {
  await userEvent.click(await bureauRetryButton());
  const dialog = await screen.findByRole("dialog");
  await userEvent.type(within(dialog).getByLabelText("Consent OTP"), "123456");
  await userEvent.click(within(dialog).getByRole("button", { name: "Retry" }));
  return dialog;
}

describe("<VerificationChecksPanel/> retry in-flight guard", () => {
  it("keeps Retry off after the client gives up waiting, until the row is written again", async () => {
    let checkedAt = "2026-10-06T08:00:00Z";
    stubPanel(() => [bureau(checkedAt)]);
    const retry = vi
      .spyOn(staffApi, "retryVerification")
      .mockRejectedValue(new VerificationRetryTimeoutError());

    renderWithClient(<VerificationChecksPanel applicationId={42} />);
    const dialog = await sendRetry();

    // The dialog's own Retry stays off — a second click would re-bill the provider.
    expect(await within(dialog).findByText(/Still running at the provider/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Retry" })).toBeDisabled();

    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(await bureauRetryButton()).toBeDisabled();
    expect(screen.getByText(/Still running at the provider/)).toBeInTheDocument();
    expect(retry).toHaveBeenCalledTimes(1);

    // The provider call finishes server-side: the row's timestamp moves, and Refresh lifts the guard.
    checkedAt = "2026-10-06T08:02:10Z";
    await userEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(async () => expect(await bureauRetryButton()).toBeEnabled());
    expect(screen.queryByText(/Still running at the provider/)).not.toBeInTheDocument();
  });

  it("stays off while the timestamp has not moved, even after a refresh", async () => {
    stubPanel(() => [bureau("2026-10-06T08:00:00Z")]);
    vi.spyOn(staffApi, "retryVerification").mockRejectedValue(
      new ApplicationApiError("Gateway Timeout", "HTTP_504", 504),
    );

    renderWithClient(<VerificationChecksPanel applicationId={42} />);
    const dialog = await sendRetry();
    await within(dialog).findByText(/Still running at the provider/);
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await userEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(staffApi.verifications).toHaveBeenCalledTimes(2));
    expect(await bureauRetryButton()).toBeDisabled();
  });

  it("does not carry one application's guard to another when the panel is re-pointed", async () => {
    // Same BUREAU timestamp on both files, so only the application half of the key tells them apart.
    stubPanel(() => [bureau("2026-10-06T08:00:00Z")]);
    vi.spyOn(staffApi, "retryVerification").mockRejectedValue(new VerificationRetryTimeoutError());

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    qc.setQueryData(["staff-me"], { id: "1", name: "Admin", role: "ADMIN", realRole: "ADMIN" });
    const { rerender } = render(
      <QueryClientProvider client={qc}>
        <VerificationChecksPanel applicationId={42} />
      </QueryClientProvider>,
    );
    const dialog = await sendRetry();
    await within(dialog).findByText(/Still running at the provider/);
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(await bureauRetryButton()).toBeDisabled();

    rerender(
      <QueryClientProvider client={qc}>
        <VerificationChecksPanel applicationId={43} />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(staffApi.verifications).toHaveBeenCalledWith(43));
    await waitFor(async () => expect(await bureauRetryButton()).toBeEnabled());
    expect(screen.queryByText(/Still running at the provider/)).not.toBeInTheDocument();
  });

  it("lets the reviewer retry again after an answer from the server", async () => {
    stubPanel(() => [bureau("2026-10-06T08:00:00Z")]);
    vi.spyOn(staffApi, "retryVerification").mockRejectedValue(
      new ApplicationApiError("OTP expired", "OTP_EXPIRED", 422),
    );

    renderWithClient(<VerificationChecksPanel applicationId={42} />);
    const dialog = await sendRetry();

    expect(await within(dialog).findByText(/OTP expired/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/Still running at the provider/)).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Retry" })).toBeEnabled();
  });

  it("labels who answered each check", async () => {
    stubPanel(() => [bureau("2026-10-06T08:00:00Z")]);
    renderWithClient(<VerificationChecksPanel applicationId={42} />);
    expect(await screen.findByText(/Answered by DIGITAP/)).toBeInTheDocument();
  });
});

function overview(partial: Partial<VerificationOverview>): VerificationOverview {
  return {
    passed: 3,
    review: 1,
    failed: 1,
    pending: 2,
    neverRun: 4,
    rows: [],
    page: 1,
    size: 25,
    total: 0,
    notStarted: [],
    notStartedTotal: 0,
    ...partial,
  };
}

describe("/staff/verifications page", () => {
  it("builds 'Not started' from the server list and never polls the KYC_PENDING queue", async () => {
    const listByStatus = vi.spyOn(staffApi, "listByStatus");
    vi.spyOn(staffApi, "verificationOverview").mockResolvedValue(
      overview({
        rows: [
          {
            applicationId: 7,
            customerId: 70,
            borrowerName: "Asha Verma",
            borrowerMobile: "9000000007",
            checkType: "PAN",
            status: "FAIL",
            provider: "DIGITAP",
            message: null,
            updatedAt: "2026-10-06T08:00:00Z",
            applicationStatus: "KYC_PENDING",
          },
        ],
        total: 1,
        notStarted: [{ applicationId: 9, customerId: 90, borrowerName: "Bilal Khan", borrowerMobile: null }],
        notStartedTotal: 260,
      }),
    );

    renderWithClient(<VerificationsDashboardPage />);

    const header = await screen.findByRole("button", { name: /Not started/ });
    expect(header).toHaveAttribute("aria-expanded", "true");
    const bucket = document.getElementById(header.getAttribute("aria-controls") ?? "");
    expect(bucket).not.toBeNull();
    expect(within(bucket as HTMLElement).getByText("Bilal Khan")).toBeInTheDocument();
    expect(within(bucket as HTMLElement).queryByText("Asha Verma")).not.toBeInTheDocument();
    expect(screen.getByText(/Showing 1 of 260, newest first/)).toBeInTheDocument();
    expect(listByStatus).not.toHaveBeenCalled();

    // Collapsing hides the cards and flips aria-expanded; nothing is persisted.
    await userEvent.click(header);
    expect(header).toHaveAttribute("aria-expanded", "false");
    expect(bucket).not.toBeVisible();
  });

  it("captions the tallies while a search is active", async () => {
    vi.spyOn(staffApi, "verificationOverview").mockResolvedValue(overview({}));
    renderWithClient(<VerificationsDashboardPage />);

    await screen.findByText("Never run");
    expect(screen.queryByText(/all undecided files/)).not.toBeInTheDocument();

    const box = screen.getByRole("textbox", { name: /Search by borrower/ });
    await userEvent.type(box, "asha{Enter}");
    expect(await screen.findByText(/Tallies \(all undecided files\)/)).toBeInTheDocument();
  });
});
