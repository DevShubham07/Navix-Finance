import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { StatusBadge, humaniseStatus, statusVariant } from "./status-badge";

/**
 * The map is the point of this primitive, so most of these assert `statusVariant` directly rather
 * than rendering — a tone regression should fail on the map, not on a class string.
 */
describe("statusVariant", () => {
  it("encodes what the operator should do, not the enum name", () => {
    // Terminal good vs terminal bad.
    expect(statusVariant("application", "CLOSED")).toBe("success");
    expect(statusVariant("application", "WRITTEN_OFF")).toBe("error");
    // Waiting on a human.
    expect(statusVariant("application", "CREDIT_EXEC_PENDING")).toBe("warning");
    expect(statusVariant("application", "DISBURSEMENT_PENDING")).toBe("warning");
    // Live and healthy.
    expect(statusVariant("application", "ACTIVE")).toBe("info");
    // Needs intervention.
    expect(statusVariant("application", "OVERDUE")).toBe("error");
    expect(statusVariant("application", "DISBURSEMENT_FAILED")).toBe("error");
    // Not started.
    expect(statusVariant("application", "DRAFT")).toBe("neutral");
  });

  it("keeps repayment 'VERIFIED' and collections 'VALIDATED' as separate vocabularies", () => {
    // A borrower repayment is verified by the Accountant; a collections payment is validated.
    // Conflating them is the mistake the exhaustive Record exists to prevent.
    expect(statusVariant("payment", "VERIFIED")).toBe("success");
    expect(statusVariant("collectionPayment", "VALIDATED")).toBe("success");
    // The other enum's terminal word is not a member of this one.
    expect(statusVariant("collectionPayment", "VERIFIED")).toBe("neutral");
  });

  it("keeps advisory checks out of the gating colours", () => {
    // Employment/EPFO never gates a transition (CLAUDE.md §14), so a FAIL must not read as a
    // blocker the way a gating check's FAIL does.
    expect(statusVariant("verification", "FAIL")).toBe("error");
    expect(statusVariant("advisory", "FAIL")).toBe("info");
    expect(statusVariant("advisory", "PASS")).toBe("info");
  });

  it("falls back to neutral for an unmapped or missing value instead of throwing", () => {
    // A backend enum can gain a member before the frontend knows about it; the register must
    // still paint.
    expect(statusVariant("application", "SOME_FUTURE_STATUS")).toBe("neutral");
    expect(statusVariant("application", null)).toBe("neutral");
    expect(statusVariant("application", undefined)).toBe("neutral");
    expect(statusVariant("application", "")).toBe("neutral");
  });
});

describe("humaniseStatus", () => {
  it("title-cases a screaming-snake enum", () => {
    expect(humaniseStatus("KYC_PENDING")).toBe("Kyc Pending");
    expect(humaniseStatus("ACTIVE")).toBe("Active");
    expect(humaniseStatus("PENDING_VERIFICATION")).toBe("Pending Verification");
  });
});

describe("<StatusBadge/>", () => {
  it("renders the humanised label and the mapped tone", () => {
    render(<StatusBadge kind="application" value="CREDIT_EXEC_PENDING" />);
    const badge = screen.getByText("Credit Exec Pending");
    expect(badge).toBeInTheDocument();
    expect(badge).toHaveClass("bg-warning-100");
  });

  it("renders an em dash, not a badge, for a missing value", () => {
    render(<StatusBadge kind="application" value={null} />);
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("lets a caller override the text while keeping the tone", () => {
    render(
      <StatusBadge kind="application" value="OVERDUE">
        12 days late
      </StatusBadge>,
    );
    const badge = screen.getByText("12 days late");
    expect(badge).toHaveClass("bg-error-100");
  });
});
