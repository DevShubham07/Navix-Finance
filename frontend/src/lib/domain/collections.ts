/**
 * Collections domain types. Loans enter collections after the late-penalty
 * window (2%/day capped at 30 days) lapses.
 */

export type DpdBucket =
  | "UPCOMING"
  | "T0_T7"
  | "T8_T30"
  | "T30_T60"
  | "T60_T90"
  | "T90_PLUS";

export type InteractionChannel = "CALL" | "SMS" | "EMAIL" | "WHATSAPP" | "FIELD_VISIT";

export type InteractionOutcome =
  | "PROMISE_TO_PAY"
  | "NO_RESPONSE"
  | "DISPUTE"
  | "PARTIAL_PAYMENT"
  | "PAID"
  | "ESCALATE";

export interface InteractionLog {
  id: string;
  caseId: string;
  channel: InteractionChannel;
  outcome: InteractionOutcome;
  notes?: string;
  officerId: string;
  occurredAt: string;
}

export interface RepaymentPlan {
  id: string;
  caseId: string;
  /** Number of installments agreed. */
  installments: number;
  installmentAmount: number;
  startDate: string;
  active: boolean;
}

export interface Settlement {
  id: string;
  caseId: string;
  /** Negotiated settlement amount (<= outstanding). */
  settlementAmount: number;
  approvedBy?: string;
  status: "PROPOSED" | "APPROVED" | "REJECTED" | "SETTLED";
  createdAt: string;
}

export interface CollectionCase {
  id: string;
  loanId: string;
  customerId: string;
  outstanding: number;
  daysPastDue: number;
  bucket: DpdBucket;
  assignedOfficerId?: string;
  interactions: InteractionLog[];
  repaymentPlan?: RepaymentPlan;
  settlement?: Settlement;
  createdAt: string;
  updatedAt: string;
}
