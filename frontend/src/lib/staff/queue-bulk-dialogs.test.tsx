import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { staffApi } from "@/lib/api/applications";
import {
  BulkActionBar,
  MIXED_REJECT_MODES_REASON,
  RejectDialog,
} from "@/components/staff/pipeline/bulk-actions";

/**
 * Lives under lib/staff beside the runner it exercises (queue-bulk.ts); it renders the bulk bar and
 * the reject dialog from `components/staff/pipeline/bulk-actions.tsx`.
 */
describe("<BulkActionBar/> reject button", () => {
  it("is enabled and clickable when the selection can be rejected", async () => {
    const onReject = vi.fn();
    render(<BulkActionBar count={2} onReject={onReject} />);
    const button = screen.getByRole("button", { name: "Reject selected" });
    expect(button).toBeEnabled();
    await userEvent.click(button);
    expect(onReject).toHaveBeenCalledTimes(1);
  });

  it("stays visible but disabled, with an explanation, for a mixed-mode selection", () => {
    render(<BulkActionBar count={2} rejectDisabledReason={MIXED_REJECT_MODES_REASON} />);
    expect(screen.getByRole("button", { name: "Reject selected" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Why is Reject selected unavailable?" })).toBeInTheDocument();
  });

  it("is absent for a role that cannot bulk reject", () => {
    render(<BulkActionBar count={2} />);
    expect(screen.queryByRole("button", { name: "Reject selected" })).not.toBeInTheDocument();
  });
});

describe("<RejectDialog/> bulk run", () => {
  function renderDialog(ids: number[]) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={qc}>
        <RejectDialog ids={ids} mode="credit" open onClose={() => {}} />
      </QueryClientProvider>,
    );
  }

  it("calls the per-id endpoint once per id, shows progress, and summarises failures", async () => {
    const gates = new Map<number, { resolve: () => void; reject: (e: unknown) => void }>();
    const spy = vi.spyOn(staffApi, "rejectLead").mockImplementation(
      (id: number) =>
        new Promise((resolve, reject) => {
          gates.set(id, { resolve: () => resolve(undefined as never), reject });
        }),
    );

    renderDialog([11, 12, 13, 14, 15, 16]);
    await userEvent.type(screen.getByLabelText("Rejection reason"), "stale payslip");
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));

    // Four in flight, never more — the rest wait for a slot.
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(4));
    expect(screen.getByRole("status")).toHaveTextContent("0 / 6 done");

    gates.get(11)!.resolve();
    gates.get(12)!.reject(new Error("SOD_VIOLATION"));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("2 / 6 done"));
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(6));

    for (const id of [13, 14, 15, 16]) gates.get(id)!.resolve();

    await waitFor(() => expect(screen.getByText(/5 rejected/)).toBeInTheDocument());
    expect(screen.getByText(/1 failed \(#12\)/)).toBeInTheDocument();
    expect(spy.mock.calls.map(([id]) => id).sort()).toEqual([11, 12, 13, 14, 15, 16]);
    expect(spy.mock.calls.every(([, reason]) => reason === "stale payslip")).toBe(true);
  });

  it("shows no running count for a single id", async () => {
    let finish!: () => void;
    vi.spyOn(staffApi, "rejectLead").mockImplementation(
      () => new Promise((resolve) => (finish = () => resolve(undefined as never))),
    );
    renderDialog([42]);
    await userEvent.type(screen.getByLabelText("Rejection reason"), "x");
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    finish();
    await waitFor(() => expect(screen.getByText(/1 rejected/)).toBeInTheDocument());
  });
});
