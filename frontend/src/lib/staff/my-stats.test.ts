import { describe, expect, it } from "vitest";
import type {
  CaseView,
  CollectionPaymentView,
  CustomerSummary,
  DecisionView,
  SettlementView,
  StaffPerformanceRow,
  StaffPerformanceSummary,
} from "@/lib/api/applications";
import { bookStats, collectionsStats, decisionStats, outcomeStats } from "./my-stats";
import { decidedCustomerIds, isMine } from "@/lib/customers/mine";

function row(overrides: Partial<StaffPerformanceRow> = {}): StaffPerformanceRow {
  return {
    staffId: 1,
    staffName: "A",
    role: "CREDIT_EXECUTIVE",
    active: true,
    accepted: 0,
    rejected: 0,
    totalActions: 0,
    activeDays: 0,
    avgTurnaroundMinutes: null,
    pendingNow: 0,
    moneyPaise: 0,
    firstActionAt: null,
    lastActionAt: null,
    callsMade: 0,
    verifiedCount: null,
    verifiedPaise: null,
    rejectedPaymentCount: null,
    ...overrides,
  };
}

function customer(overrides: Partial<CustomerSummary> = {}): CustomerSummary {
  return {
    customerId: 1,
    name: "X",
    pan: null,
    mobile: null,
    applicationCount: 1,
    loanCount: 1,
    latestStatus: "ACTIVE",
    totalOutstandingPaise: 0,
    ...overrides,
  };
}

describe("decisionStats", () => {
  it("empty rows/daily → every rate/average is null, not 0", () => {
    const summary: StaffPerformanceSummary = { rows: [], daily: [], callTrackingSince: "2026-01-01" };
    const stats = decisionStats(summary, null);
    expect(stats.approvalRate).toBeNull();
    expect(stats.avgTurnaroundMinutes).toBeNull();
    expect(stats.actionsPerActiveDay).toBeNull();
    expect(stats.busiestDay).toBeNull();
    expect(stats.decided).toBe(0);
  });

  it("a row with avgTurnaroundMinutes null is skipped from the weighted mean, not counted as 0", () => {
    const summary: StaffPerformanceSummary = {
      rows: [
        row({ totalActions: 10, avgTurnaroundMinutes: 60 }),
        row({ totalActions: 100, avgTurnaroundMinutes: null }),
      ],
      daily: [],
      callTrackingSince: "2026-01-01",
    };
    const stats = decisionStats(summary, null);
    // weighted purely by the first row: 60, not dragged toward 0 by the second row's 100 weight
    expect(stats.avgTurnaroundMinutes).toBe(60);
  });

  it("weights the mean by totalActions across measurable rows", () => {
    const summary: StaffPerformanceSummary = {
      rows: [
        row({ totalActions: 1, avgTurnaroundMinutes: 10 }),
        row({ totalActions: 3, avgTurnaroundMinutes: 50 }),
      ],
      daily: [],
      callTrackingSince: "2026-01-01",
    };
    const stats = decisionStats(summary, null);
    expect(stats.avgTurnaroundMinutes).toBe((1 * 10 + 3 * 50) / 4);
  });

  it("callsTracked is false when the window starts before callTrackingSince", () => {
    const summary: StaffPerformanceSummary = { rows: [], daily: [], callTrackingSince: "2026-06-01" };
    expect(decisionStats(summary, "2026-05-01").callsTracked).toBe(false);
    expect(decisionStats(summary, "2026-06-01").callsTracked).toBe(true);
    expect(decisionStats(summary, "2026-07-01").callsTracked).toBe(true);
    expect(decisionStats(summary, null).callsTracked).toBe(false);
  });

  it("busiestDay picks the max, and approvalRate/actionsPerActiveDay compute when denominators are real", () => {
    const summary: StaffPerformanceSummary = {
      rows: [row({ totalActions: 10, accepted: 6, rejected: 4, activeDays: 5 })],
      daily: [
        { date: "2026-01-01", actions: 3 },
        { date: "2026-01-02", actions: 9 },
      ],
      callTrackingSince: "2026-01-01",
    };
    const stats = decisionStats(summary, null);
    expect(stats.busiestDay).toEqual({ date: "2026-01-02", actions: 9 });
    expect(stats.approvalRate).toBe(0.6);
    expect(stats.actionsPerActiveDay).toBe(2);
  });
});

describe("outcomeStats", () => {
  it("empty decisions/book → every rate is null", () => {
    const stats = outcomeStats([], []);
    expect(stats.overdueRate).toBeNull();
    expect(stats.parPct).toBeNull();
    expect(stats.avgSanctionedPaise).toBeNull();
    expect(stats.avgScoreApproved).toBeNull();
    expect(stats.avgScoreRejected).toBeNull();
    expect(stats.approvedCount).toBe(0);
  });

  it("a decision whose customerId is not in the book is skipped silently, no throw", () => {
    const decisions: DecisionView[] = [
      {
        applicationId: 1,
        customerId: 999,
        customerName: null,
        pan: null,
        action: "SANCTION",
        fromStatus: null,
        toStatus: "SANCTIONED",
        at: "2026-01-01T00:00:00Z",
        amountPaise: null,
        salaryCreditDay: null,
        repaymentDate: null,
        assigneeId: null,
        assigneeName: null,
        txnRef: null,
        remark: null,
        notes: null,
      },
    ];
    expect(() => outcomeStats(decisions, [])).not.toThrow();
    expect(outcomeStats(decisions, []).approvedCount).toBe(0);
  });

  it("uses the backend's action vocabulary: ADMIN_FORCE_DISBURSE/CANCEL count, dead V45 actions do not", () => {
    // Pins ACCEPT_ACTIONS/REJECT_ACTIONS to DecisionHistoryService (navix-loan). If these drift, the
    // dashboard shows outcome counts that contradict the backend's own approved/rejected numbers
    // rendered beside them. EXEC_APPROVE/HEAD_APPROVE/DISB_ACCEPT/VALIDATE_FAIL are dead since V45/V48.
    const decide = (customerId: number, action: string): DecisionView => ({
      applicationId: customerId,
      customerId,
      customerName: null,
      pan: null,
      action,
      fromStatus: null,
      toStatus: "X",
      at: "2026-01-01T00:00:00Z",
      amountPaise: null,
      salaryCreditDay: null,
      repaymentDate: null,
      assigneeId: null,
      assigneeName: null,
      txnRef: null,
      remark: null,
      notes: null,
    });
    const book: CustomerSummary[] = [1, 2, 3, 4].map((customerId) =>
      customer({ customerId, creditScore: 700 }),
    );

    // Live approvals per the backend set.
    for (const a of ["KYC_APPROVE", "SANCTION", "VALIDATE_SUCCESS", "ADMIN_FORCE_DISBURSE"]) {
      expect(outcomeStats([decide(1, a)], book).approvedCount).toBe(1);
    }
    // Live rejections — counted as rejections, never as approvals.
    for (const a of ["KYC_REJECT", "REJECT_LEAD", "DISB_REJECT", "CANCEL"]) {
      expect(outcomeStats([decide(1, a)], book).approvedCount).toBe(0);
      expect(outcomeStats([decide(1, a)], book).avgScoreRejected).toBe(700);
    }
    // Retired and system actions count as neither.
    for (const a of ["EXEC_APPROVE", "HEAD_APPROVE", "DISB_ACCEPT", "VALIDATE_FAIL", "ASSIGN", "AUTO_REJECT_BLOCKED"]) {
      const st = outcomeStats([decide(1, a)], book);
      expect(st.approvedCount).toBe(0);
      expect(st.avgScoreRejected).toBeNull();
    }
  });

  it("buckets matched approved customers with segmentOf and computes parPct from the two subsets", () => {
    const decisions: DecisionView[] = [1, 2, 3].map((id) => ({
      applicationId: id,
      customerId: id,
      customerName: null,
      pan: null,
      action: "SANCTION",
      fromStatus: null,
      toStatus: "SANCTIONED",
      at: "2026-01-01T00:00:00Z",
      amountPaise: null,
      salaryCreditDay: null,
      repaymentDate: null,
      assigneeId: null,
      assigneeName: null,
      txnRef: null,
      remark: null,
      notes: null,
    }));
    const book: CustomerSummary[] = [
      customer({ customerId: 1, loanStatus: "OVERDUE", latestStatus: "ACTIVE", totalOutstandingPaise: 1000 }),
      customer({ customerId: 2, loanStatus: "ACTIVE", latestStatus: "ACTIVE", totalOutstandingPaise: 4000 }),
      customer({ customerId: 3, latestStatus: "CLOSED", totalOutstandingPaise: 0 }),
    ];
    const stats = outcomeStats(decisions, book);
    expect(stats.approvedCount).toBe(3);
    expect(stats.nowOverdue).toBe(1);
    expect(stats.stillLive).toBe(1);
    expect(stats.repaidClean).toBe(1);
    expect(stats.overdueRate).toBe(1 / 3);
    expect(stats.parPct).toBe(1000 / 4000);
  });
});

describe("bookStats", () => {
  const today = new Date("2026-08-22T00:00:00");

  it("empty book → concentrationPct and other rates are null, counts are 0", () => {
    const stats = bookStats([], today);
    expect(stats.concentrationPct).toBeNull();
    expect(stats.avgTicketPaise).toBeNull();
    expect(stats.avgCreditScore).toBeNull();
    expect(stats.total).toBe(0);
  });

  it("DPD boundaries: exactly 30 -> d1to30, 31 -> d31to60, 60 -> d31to60, 61 -> d60plus", () => {
    const iso = (daysAgo: number) => {
      const d = new Date(today);
      d.setDate(d.getDate() - daysAgo);
      const pad = (n: number) => String(n).padStart(2, "0");
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    };
    const book: CustomerSummary[] = [
      customer({ customerId: 1, loanDueDate: iso(30) }),
      customer({ customerId: 2, loanDueDate: iso(31) }),
      customer({ customerId: 3, loanDueDate: iso(60) }),
      customer({ customerId: 4, loanDueDate: iso(61) }),
    ];
    const stats = bookStats(book, today);
    expect(stats.dpd.d1to30).toBe(1);
    expect(stats.dpd.d31to60).toBe(2);
    expect(stats.dpd.d60plus).toBe(1);
  });

  it("concentrationPct is null when total outstanding is 0", () => {
    const book: CustomerSummary[] = [customer({ totalOutstandingPaise: 0 })];
    expect(bookStats(book, today).concentrationPct).toBeNull();
  });

  it("concentrationPct computes largest/total when outstanding is real", () => {
    const book: CustomerSummary[] = [
      customer({ customerId: 1, totalOutstandingPaise: 3000 }),
      customer({ customerId: 2, totalOutstandingPaise: 1000 }),
    ];
    const stats = bookStats(book, today);
    expect(stats.concentrationPct).toBe(3000 / 4000);
  });
});

describe("collectionsStats", () => {
  function caseRow(overrides: Partial<CaseView> = {}): CaseView {
    return {
      id: "c1",
      loanId: 1,
      assignedOfficerId: 1,
      assignedOfficerName: null,
      createdAt: "2026-01-01T00:00:00Z",
      dpd: 0,
      bucket: "T0_T7",
      loanStatus: "OVERDUE",
      borrowerName: null,
      outstandingPaise: 500,
      dueDate: null,
      ...overrides,
    };
  }

  it("recoveryRate is null when myCasesOutstandingPaise is 0", () => {
    const stats = collectionsStats([], [], [], 1);
    expect(stats.recoveryRate).toBeNull();
    expect(stats.myCases).toEqual([]);
  });

  it("filters cases/payments/settlements by staffId and computes recoveryRate", () => {
    const cases: CaseView[] = [
      caseRow({ id: "c1", assignedOfficerId: 1, outstandingPaise: 1000 }),
      caseRow({ id: "c2", assignedOfficerId: 2, outstandingPaise: 5000 }),
    ];
    const payments: CollectionPaymentView[] = [
      {
        id: "p1",
        collectionCaseId: "c1",
        loanId: 1,
        kind: "PART_PAYMENT",
        amountPaise: 400,
        paidOn: null,
        txnRef: null,
        proofRef: null,
        settlementId: null,
        status: "VALIDATED",
        raisedBy: 1,
        raisedByName: null,
        raisedAt: "2026-01-01T00:00:00Z",
        validatedBy: null,
        validatedByName: null,
        validatedAt: null,
        remarks: null,
        ledgerPaymentId: 1,
        borrowerName: null,
      },
    ];
    const settlements: SettlementView[] = [
      {
        id: "s1",
        collectionCaseId: "c1",
        settlementAmountPaise: 300,
        proposedBy: 1,
        proposedByName: null,
        approvedBy: 9,
        approvedByName: null,
        rejectedBy: null,
        rejectedByName: null,
        status: "APPROVED",
        createdAt: "2026-01-01T00:00:00Z",
        approvedAt: "2026-01-02T00:00:00Z",
        rejectedAt: null,
      },
    ];
    const stats = collectionsStats(cases, settlements, payments, 1);
    expect(stats.myCases.map((c) => c.id)).toEqual(["c1"]);
    expect(stats.myCasesOutstandingPaise).toBe(1000);
    expect(stats.recoveredPaise).toBe(400);
    expect(stats.recoveryRate).toBe(400 / 1000);
    expect(stats.settlementsProposed).toEqual({ proposed: 0, approved: 1, rejected: 0 });
    expect(stats.settlementsApproved).toBe(0);
  });
});

describe("mine.ts", () => {
  it("isMine: allocated owner or a decided customer", () => {
    const decided = decidedCustomerIds([
      { customerId: 5 } as DecisionView,
      { customerId: null } as DecisionView,
    ]);
    expect(isMine(customer({ customerId: 5, ownerStaffId: null }), 1, decided)).toBe(true);
    expect(isMine(customer({ customerId: 6, ownerStaffId: 1 }), 1, decided)).toBe(true);
    expect(isMine(customer({ customerId: 7, ownerStaffId: 2 }), 1, decided)).toBe(false);
  });
});
