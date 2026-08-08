import { describe, expect, it } from "vitest";
import { nextBorrowerMilestone } from "./borrower-next-milestone";

const TODAY = new Date(2026, 7, 8);

describe("nextBorrowerMilestone", () => {
  it("asks a borrower without an application to complete it", () => {
    expect(nextBorrowerMilestone(null, null, TODAY)).toMatchObject({
      title: "Complete your application",
      actionLabel: "Continue application",
      href: "/signup/start",
      icon: "application",
    });
  });

  it("resumes a draft application", () => {
    expect(nextBorrowerMilestone("DRAFT", null, TODAY).href).toBe("/signup/start");
  });

  it.each(["KYC_PENDING", "KYC_APPROVED", "PRE_APPROVED", "REVIEW_PENDING", "CREDIT_EXEC_PENDING"] as const)(
    "shows credit review for %s",
    (status) => {
      expect(nextBorrowerMilestone(status, null, TODAY)).toMatchObject({
        title: "Credit decision",
        actionLabel: "Track status",
        href: "/loan/status",
        icon: "review",
      });
    },
  );

  it("continues the offer journey after sanction", () => {
    expect(nextBorrowerMilestone("SANCTIONED", null, TODAY)).toMatchObject({
      title: "Complete your offer steps",
      actionLabel: "Continue loan",
      href: "/loan/amount",
      icon: "offer",
    });
  });

  it.each(["DISBURSEMENT_PENDING", "ACCOUNTANT_PENDING", "DISBURSEMENT_FAILED", "DISBURSED"] as const)(
    "shows disbursal progress for %s",
    (status) => {
      expect(nextBorrowerMilestone(status, null, TODAY)).toMatchObject({
        title: "Disbursal in progress",
        actionLabel: "Track status",
        href: "/loan/status",
        icon: "disbursal",
      });
    },
  );

  it("counts calendar days until the contractual due date", () => {
    expect(nextBorrowerMilestone("ACTIVE", "2026-08-12", TODAY)).toMatchObject({
      title: "Repayment due in 4 days",
      detail: "Your contractual due date is 12 Aug 2026.",
      actionLabel: "Repay / prepay",
      href: "/repay",
      icon: "repayment",
    });
  });

  it("shows a due-today milestone on salary day", () => {
    expect(nextBorrowerMilestone("ACTIVE", "2026-08-08", TODAY).title).toBe("Repayment due today");
  });

  it("shows the penalty-free grace day exactly one day after the due date", () => {
    expect(nextBorrowerMilestone("ACTIVE", "2026-08-07", TODAY)).toMatchObject({
      title: "Grace day — pay today",
      detail: "Pay today before the daily late penalty begins tomorrow.",
      actionLabel: "Pay today",
    });
  });

  it("shows overdue days even when the ACTIVE status has not refreshed yet", () => {
    expect(nextBorrowerMilestone("ACTIVE", "2026-08-05", TODAY).title).toBe("Payment overdue by 3 days");
  });

  it("shows overdue days for an overdue application", () => {
    expect(nextBorrowerMilestone("OVERDUE", "2026-08-01", TODAY)).toMatchObject({
      title: "Payment overdue by 7 days",
      actionLabel: "Pay now",
      href: "/repay",
      icon: "overdue",
    });
  });

  it("falls back safely when an active loan has no due date", () => {
    expect(nextBorrowerMilestone("ACTIVE", null, TODAY)).toMatchObject({
      title: "Track your repayment",
      actionLabel: "View repayment",
      href: "/repay",
    });
  });

  it("invites a borrower with a closed loan to borrow again", () => {
    expect(nextBorrowerMilestone("CLOSED", null, TODAY)).toMatchObject({
      title: "You can borrow again",
      actionLabel: "Borrow again",
      href: "/reloan",
      icon: "reborrow",
    });
  });

  it.each(["KYC_REJECTED", "REJECTED", "CANCELLED", "DEFAULTED", "WRITTEN_OFF"] as const)(
    "shows an application-closed milestone for %s",
    (status) => {
      expect(nextBorrowerMilestone(status, null, TODAY)).toMatchObject({
        title: "Application closed",
        actionLabel: "View status",
        href: "/loan/status",
        icon: "closed",
      });
    },
  );
});
