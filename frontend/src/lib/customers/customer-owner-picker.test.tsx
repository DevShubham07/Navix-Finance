/**
 * `CustomerOwnerPicker` (components/staff/customer-owner-picker.tsx) defers the staff roster to the
 * first open of the picker instead of loading it on mount for every picker — while still showing the
 * current owner, and still loading eagerly when the caller gave an owner id but no name to show.
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { staffApi, type StaffSummary } from "@/lib/api/applications";
import { CustomerOwnerPicker } from "@/components/staff/customer-owner-picker";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
// The real gate resolves the signed-in role over the network; the role gate itself is not under test.
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

const ROSTER: StaffSummary[] = [
  { id: 7, name: "Asha Verma", role: "CREDIT_EXECUTIVE", active: true },
  { id: 9, name: "Bilal Khan", role: "CREDIT_EXECUTIVE", active: true },
];

function renderPicker(props: Partial<React.ComponentProps<typeof CustomerOwnerPicker>> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CustomerOwnerPicker customerId={42} {...props} />
    </QueryClientProvider>,
  );
}

describe("<CustomerOwnerPicker/> roster loading", () => {
  it("does not fetch the roster on mount when the owner's name is known", () => {
    const spy = vi.spyOn(staffApi, "creditExecutives").mockResolvedValue(ROSTER);
    renderPicker({ ownerStaffId: 7, ownerName: "Asha Verma" });
    expect(spy).not.toHaveBeenCalled();
    // The current owner is still what the select shows, not "Unallocated".
    expect(screen.getByLabelText("Assign to")).toHaveDisplayValue("Asha Verma");
  });

  it("fetches the roster on first focus, once per owner role, and lists it", async () => {
    const spy = vi.spyOn(staffApi, "creditExecutives").mockResolvedValue(ROSTER);
    renderPicker({ ownerStaffId: null, ownerName: null });
    expect(spy).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Assign to")).toHaveDisplayValue("Unallocated");

    fireEvent.focus(screen.getByLabelText("Assign to"));

    await waitFor(() => expect(screen.getByRole("option", { name: "Bilal Khan (CREDIT_EXECUTIVE)" })).toBeInTheDocument());
    // The shared ["staff-picker", role] keys: one request per role an ADMIN may assign to.
    expect(spy.mock.calls.map(([role]) => role).sort()).toEqual(
      ["COLLECTION_EXECUTIVE", "CREDIT_EXECUTIVE", "TELECALLER"],
    );
  });

  it("fetches on pointer down as well", async () => {
    const spy = vi.spyOn(staffApi, "creditExecutives").mockResolvedValue(ROSTER);
    renderPicker({ allowedRoles: ["TELECALLER"] });
    fireEvent.pointerDown(screen.getByLabelText("Assign to"));
    await waitFor(() => expect(spy).toHaveBeenCalledWith("TELECALLER"));
  });

  it("still loads on mount when given an owner id without a name (it needs the roster to label them)", async () => {
    const spy = vi.spyOn(staffApi, "creditExecutives").mockResolvedValue(ROSTER);
    renderPicker({ ownerStaffId: 9, allowedRoles: ["CREDIT_EXECUTIVE"], compact: true });
    await waitFor(() => expect(spy).toHaveBeenCalledWith("CREDIT_EXECUTIVE"));
    await waitFor(() =>
      expect(screen.getByLabelText("Assign to")).toHaveDisplayValue("Bilal Khan (CREDIT_EXECUTIVE)"),
    );
  });

  it("says so when the roster cannot be loaded", async () => {
    vi.spyOn(staffApi, "creditExecutives").mockRejectedValue(new Error("boom"));
    renderPicker({ allowedRoles: ["TELECALLER"] });
    fireEvent.focus(screen.getByLabelText("Assign to"));
    expect(await screen.findByText("Could not load the staff list.")).toBeInTheDocument();
  });
});
