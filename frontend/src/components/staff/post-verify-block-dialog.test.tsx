/**
 * `PostVerifyBlockDialog` (components/staff/post-verify-block-dialog.tsx): the "should they borrow
 * again?" prompt both repayment-verify call sites open after a verify. The default is eligible and
 * costs no API call; blocking needs a reason and only then calls `customersApi.block`.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { customersApi, type CustomerDetail } from "@/lib/api/applications";
import { PostVerifyBlockDialog } from "@/components/staff/post-verify-block-dialog";

function detail(over: Partial<CustomerDetail> = {}): CustomerDetail {
  return {
    customerId: 42,
    profile: null,
    applications: [],
    loans: [],
    payments: [],
    blocked: false,
    blockedReason: null,
    ...over,
  };
}

function renderDialog(props: Partial<React.ComponentProps<typeof PostVerifyBlockDialog>> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onClose = vi.fn();
  const onBlocked = vi.fn();
  const utils = render(
    <QueryClientProvider client={qc}>
      <PostVerifyBlockDialog
        target={{ customerId: 42, customerName: "Asha Rao" }}
        onClose={onClose}
        onBlocked={onBlocked}
        {...props}
      />
    </QueryClientProvider>,
  );
  return { ...utils, onClose, onBlocked, qc };
}

describe("<PostVerifyBlockDialog/>", () => {
  it("asks about the named customer and 'Yes, keep eligible' is the focused default that only closes", async () => {
    vi.spyOn(customersApi, "get").mockResolvedValue(detail());
    const block = vi.spyOn(customersApi, "block");
    const { onClose } = renderDialog();

    expect(screen.getByRole("heading", { name: "Payment verified" })).toBeInTheDocument();
    expect(screen.getByText(/Should/)).toHaveTextContent("Should Asha Rao be able to take another loan?");
    const yes = screen.getByRole("button", { name: "Yes, keep eligible" });
    // First focusable in DOM order → the Dialog's focus trap lands here, so Enter keeps them eligible.
    expect(yes).toHaveFocus();

    fireEvent.click(yes);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(block).not.toHaveBeenCalled();
  });

  it("blocking reveals a required reason, refuses an empty one, then calls customersApi.block", async () => {
    vi.spyOn(customersApi, "get").mockResolvedValue(detail());
    const block = vi
      .spyOn(customersApi, "block")
      .mockResolvedValue({ customerId: 42, blocked: true, blockedReason: "Bounced twice" });
    const { onClose, onBlocked } = renderDialog();

    // No reason field until the quiet secondary path is chosen.
    expect(screen.queryByLabelText(/Reason for blocking/)).not.toBeInTheDocument();
    // Disabled until the block state has loaded, so an already-blocked customer is never re-asked.
    const no = screen.getByRole("button", { name: "No — block future loans" });
    await waitFor(() => expect(no).toBeEnabled());
    fireEvent.click(no);

    const reason = screen.getByLabelText(/Reason for blocking/);
    const confirm = screen.getByRole("button", { name: /Block customer/ });
    expect(confirm).toBeDisabled();
    fireEvent.click(confirm);
    expect(block).not.toHaveBeenCalled();

    fireEvent.change(reason, { target: { value: "  Bounced twice  " } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);

    await waitFor(() => expect(block).toHaveBeenCalledWith(42, "Bounced twice"));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(onBlocked).toHaveBeenCalledTimes(1);
  });

  it("does not re-ask about a customer who is already blocked", async () => {
    vi.spyOn(customersApi, "get").mockResolvedValue(detail({ blocked: true, blockedReason: "Fraudulent payslip" }));
    const block = vi.spyOn(customersApi, "block");
    renderDialog();

    await waitFor(() => expect(screen.getByTestId("already-blocked")).toHaveTextContent("Already blocked · Fraudulent payslip"));
    expect(screen.queryByRole("button", { name: "No — block future loans" })).not.toBeInTheDocument();
    expect(screen.queryByText(/take another loan/)).not.toBeInTheDocument();
    expect(block).not.toHaveBeenCalled();
  });

  it("renders nothing and fetches nothing without a target", () => {
    const get = vi.spyOn(customersApi, "get");
    renderDialog({ target: null });

    expect(screen.queryByRole("heading", { name: "Payment verified" })).not.toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });
});
