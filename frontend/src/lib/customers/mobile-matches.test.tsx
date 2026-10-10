import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { customersApi, type CustomerDetail, type MobileMatch, type MobileMatchView } from "@/lib/api/applications";
import { CustomerTabBody } from "@/components/staff/customer-tabs";
import { BureauMobilesStrip, MobileMatchDrawer, MobileReuseStrip, howUsed } from "@/components/staff/mobile-matches";
import { Dialog } from "@/components/ui/dialog";
import { Drawer } from "@/components/ui/drawer";

vi.mock("@/lib/auth/staff-session", () => ({
  useStaffSession: () => ({ session: { role: "ADMIN" }, loading: false }),
}));
vi.mock("@/components/staff/live-pipeline", () => ({
  PermissionGate: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  errMessage: (e: unknown) => String(e),
}));

const base: MobileMatch = {
  kind: "BORROWER", mobile: "9876543210", inBook: true, customerId: 7, applicationId: 11,
  applicationStatus: "ACTIVE", borrowerName: "Asha", contactName: null, relation: null,
  leadId: null, leadName: null, leadSource: null, addedBy: null, at: "2026-09-01T00:00:00Z",
};
const view = (over: Partial<MobileMatchView> = {}): MobileMatchView => ({
  bureau: null,
  checked: [{ mobile: "9876543210", sources: ["REGISTERED"], referenceName: null }],
  matches: [base],
  ...over,
});

function wrap(ui: React.ReactNode, v: MobileMatchView) {
  vi.spyOn(customersApi, "mobileMatches").mockResolvedValue(v);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

afterEach(() => vi.restoreAllMocks());

describe("howUsed", () => {
  it("words each kind and redacts out-of-book", () => {
    expect(howUsed(base)).toContain("Registered mobile of Asha — application #11 (");
    expect(howUsed({ ...base, kind: "BUREAU" })).toBe("Listed in Asha's credit bureau report — application #11");
    expect(howUsed({ ...base, kind: "REFERENCE", contactName: "Ravi", relation: "Friend" })).toBe(
      "Given as reference 'Ravi' (Friend) by Asha on application #11",
    );
    expect(howUsed({ ...base, kind: "LEAD", leadName: "Z", addedBy: "Sam", leadSource: "DSA" })).toMatch(
      /^Lead 'Z' added by Sam via DSA on /,
    );
    expect(howUsed({ ...base, inBook: false })).toBe("Used by a customer outside your book");
  });
});

describe("MobileReuseStrip + drawer", () => {
  it("shows the count and is hidden at zero", async () => {
    const { unmount } = wrap(<MobileReuseStrip customerId={1} onOpen={() => {}} />, view());
    expect(await screen.findByText(/Mobile used in 1 other case/)).toBeTruthy();
    unmount();
    const r2 = wrap(
      <MobileReuseStrip customerId={1} onOpen={() => {}} />,
      view({ matches: [base, { ...base, applicationId: 12 }, { ...base, customerId: 8 }, { ...base, inBook: false, customerId: null }] }),
    );
    expect(await screen.findByText(/Mobile used in 4 other cases across 3 customers/)).toBeTruthy();
    r2.unmount();
    wrap(<MobileReuseStrip customerId={1} onOpen={() => {}} />, view({ matches: [] }));
    await waitFor(() => expect(customersApi.mobileMatches).toHaveBeenCalled());
    expect(screen.queryByText(/Mobile used in/)).toBeNull();
  });

  it("Open case closes the drawer and calls the handler; out-of-book has no button", async () => {
    const onClose = vi.fn();
    const onOpenApplication = vi.fn();
    wrap(
      <MobileMatchDrawer customerId={1} open onClose={onClose} onOpenApplication={onOpenApplication} />,
      view({ matches: [base, { ...base, mobile: "9000000000", inBook: false, applicationId: null, customerId: null }] }),
    );
    expect(await screen.findByText("Used by a customer outside your book")).toBeTruthy();
    const buttons = screen.getAllByRole("button", { name: "Open case" });
    expect(buttons).toHaveLength(1);
    // Only the in-book card carries a date.
    expect(screen.getAllByText(/Sept? 2026/)).toHaveLength(1);
    fireEvent.click(buttons[0]);
    expect(onClose).toHaveBeenCalled();
    expect(onOpenApplication).toHaveBeenCalledWith(11);
  });
});

describe("BureauMobilesStrip", () => {
  const bureau = (over = {}) => ({
    provider: "CRIF", applicationId: 1, pulledAt: "2026-09-01T00:00:00Z", identityMismatch: false, numbers: [], ...over,
  });
  const num = { value: "9876543210", normalized: "9876543210", kind: "MOBILE" as const, reportedDate: null, source: "CRIF" as const, context: "HDFC", registered: true };

  it("renders registered badge, masked style and mismatch banner", async () => {
    wrap(
      <BureauMobilesStrip customerId={1} />,
      view({ bureau: bureau({ identityMismatch: true, numbers: [num, { ...num, value: "98XXXX3210", normalized: null, kind: "MASKED", registered: false }] }) }),
    );
    expect(await screen.findByText("Registered")).toBeTruthy();
    expect(screen.getByText("Registered").className).toContain("bg-navy");
    expect(screen.getByText("MASKED").closest("[data-kind]")?.className).toContain("text-ink/60");
    expect(screen.getByText(/may belong to another person/)).toBeTruthy();
  });

  it("shows the empty states", async () => {
    const { unmount } = wrap(<BureauMobilesStrip customerId={1} />, view({ bureau: null }));
    expect(await screen.findByText("No bureau report yet")).toBeTruthy();
    unmount();
    wrap(<BureauMobilesStrip customerId={1} />, view({ bureau: bureau() }));
    expect(await screen.findByText("The report lists no phone numbers")).toBeTruthy();
  });
});

describe("Dedupe section", () => {
  it("renders checked numbers and matches", async () => {
    vi.spyOn(customersApi, "dedupe").mockResolvedValue({
      aadhaar: { status: null, applicationId: null, otherCustomerIds: [], message: null },
      blocklistHits: [],
      rejection: null,
    } as never);
    const detail = { customerId: 42, profile: null, applications: [], loans: [], payments: [] } as unknown as CustomerDetail;
    wrap(<CustomerTabBody tab="dedupe" detail={detail} customerId={42} />, view());
    expect(await screen.findByText("Registered")).toBeTruthy();
    expect(screen.getByText(/Registered mobile of Asha/)).toBeTruthy();
  });
});

describe("Esc closes only the topmost modal", () => {
  it("leaves the dialog open when the drawer on top handles Escape", () => {
    const closeDialog = vi.fn();
    const closeDrawer = vi.fn();
    render(
      <>
        <Dialog open onClose={closeDialog} aria-label="d">x</Dialog>
        <Drawer open onClose={closeDrawer} aria-label="w">y</Drawer>
      </>,
    );
    return waitFor(() => {
      expect(document.querySelectorAll('[aria-modal="true"]')).toHaveLength(2);
    }).then(() => {
      fireEvent.keyDown(document, { key: "Escape" });
      expect(closeDrawer).toHaveBeenCalledTimes(1);
      expect(closeDialog).not.toHaveBeenCalled();
    });
  });
});
