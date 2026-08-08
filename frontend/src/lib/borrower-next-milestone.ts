import type { ApplicationStatus } from "./api/applications";
import { daysBetween } from "./calc/loan-math";

export type BorrowerMilestoneIcon =
  | "application"
  | "review"
  | "offer"
  | "disbursal"
  | "repayment"
  | "overdue"
  | "reborrow"
  | "closed";

export interface BorrowerNextMilestone {
  title: string;
  detail: string;
  actionLabel: string;
  href: string;
  icon: BorrowerMilestoneIcon;
}

const RELEASE_STATUSES = new Set<ApplicationStatus>([
  "CREDIT_HEAD_APPROVED",
  "DISBURSEMENT_PENDING",
  "ACCOUNTANT_PENDING",
  "DISBURSEMENT_FAILED",
  "DISBURSED",
]);

const UNSUCCESSFUL_TERMINAL_STATUSES = new Set<ApplicationStatus>([
  "KYC_REJECTED",
  "REJECTED",
  "CANCELLED",
  "DEFAULTED",
  "WRITTEN_OFF",
]);

/** Maps the live loan lifecycle to the borrower's single most useful next action. */
export function nextBorrowerMilestone(
  status: ApplicationStatus | null | undefined,
  dueDate: string | null | undefined,
  today = new Date(),
): BorrowerNextMilestone {
  if (!status || status === "DRAFT") {
    return {
      title: "Complete your application",
      detail: "Finish the remaining details so we can review your application.",
      actionLabel: "Continue application",
      href: "/signup/start",
      icon: "application",
    };
  }

  if (status === "SANCTIONED") {
    return {
      title: "Complete your offer steps",
      detail: "Review your approved offer and finish the steps before disbursal.",
      actionLabel: "Continue loan",
      href: "/loan/amount",
      icon: "offer",
    };
  }

  if (RELEASE_STATUSES.has(status)) {
    return {
      title: "Disbursal in progress",
      detail: "Your approved advance is moving through the final release checks.",
      actionLabel: "Track status",
      href: "/loan/status",
      icon: "disbursal",
    };
  }

  if (status === "ACTIVE" || status === "OVERDUE") {
    return repaymentMilestone(status, dueDate, today);
  }

  if (status === "CLOSED") {
    return {
      title: "You can borrow again",
      detail: "Your previous advance is fully repaid and closed.",
      actionLabel: "Borrow again",
      href: "/reloan",
      icon: "reborrow",
    };
  }

  if (UNSUCCESSFUL_TERMINAL_STATUSES.has(status)) {
    return {
      title: "Application closed",
      detail: "View your application status for the latest decision and details.",
      actionLabel: "View status",
      href: "/loan/status",
      icon: "closed",
    };
  }

  return {
    title: "Credit decision",
    detail: "Our review team is working on your application.",
    actionLabel: "Track status",
    href: "/loan/status",
    icon: "review",
  };
}

function repaymentMilestone(
  status: "ACTIVE" | "OVERDUE",
  dueDate: string | null | undefined,
  today: Date,
): BorrowerNextMilestone {
  const due = parseCalendarDate(dueDate);
  if (!due) {
    return {
      title: status === "OVERDUE" ? "Payment overdue" : "Track your repayment",
      detail: "Open your repayment page for the latest amount and due-date details.",
      actionLabel: status === "OVERDUE" ? "Pay now" : "View repayment",
      href: "/repay",
      icon: status === "OVERDUE" ? "overdue" : "repayment",
    };
  }

  const daysUntilDue = daysBetween(today, due);
  const dueLabel = formatCalendarDate(due);

  if (status === "OVERDUE" || daysUntilDue < -1) {
    const overdueDays = Math.max(1, -daysUntilDue);
    return {
      title: `Payment overdue by ${overdueDays} ${dayWord(overdueDays)}`,
      detail: `Your contractual due date was ${dueLabel}.`,
      actionLabel: "Pay now",
      href: "/repay",
      icon: "overdue",
    };
  }

  if (daysUntilDue === -1) {
    return {
      title: "Grace day — pay today",
      detail: "Pay today before the daily late penalty begins tomorrow.",
      actionLabel: "Pay today",
      href: "/repay",
      icon: "repayment",
    };
  }

  if (daysUntilDue === 0) {
    return {
      title: "Repayment due today",
      detail: `Your contractual due date is ${dueLabel}.`,
      actionLabel: "Pay today",
      href: "/repay",
      icon: "repayment",
    };
  }

  return {
    title: `Repayment due in ${daysUntilDue} ${dayWord(daysUntilDue)}`,
    detail: `Your contractual due date is ${dueLabel}.`,
    actionLabel: "Repay / prepay",
    href: "/repay",
    icon: "repayment",
  };
}

/** Parse an API LocalDate without letting UTC conversion shift the calendar day. */
function parseCalendarDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(year, month - 1, day);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
    return null;
  }
  return date;
}

function formatCalendarDate(date: Date): string {
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(date);
}

function dayWord(days: number): "day" | "days" {
  return days === 1 ? "day" : "days";
}
