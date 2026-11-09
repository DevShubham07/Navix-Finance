import { paiseToINR } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

/**
 * Turns the machine-readable notes the flow service writes on an application event
 * ("amountPaise=500000 salaryCreditDay=30 projectedRepaymentDate=2026-11-30 — looks fine") into a
 * line a reviewer can read ("Amount ₹5,000 · Salary day 30 · Repayment date 30 Nov 2026 · looks
 * fine"). Keys it does not know are kept as written; free text after " — " is kept verbatim.
 * Mirrors the backend's DecisionNotes.parse, which only the My decisions page uses.
 */
export function formatEventNotes(notes: string | null | undefined): string | null {
  if (!notes) return null;
  const [machine, ...rest] = notes.split(" — ");
  const remark = rest.join(" — ").trim();
  const parts: string[] = [];
  let anyKnown = false;
  for (const token of machine.trim().split(/\s+/)) {
    const m = /^([A-Za-z]+)=(.+)$/.exec(token);
    if (!m) {
      parts.push(token);
      continue;
    }
    const [, key, value] = m;
    const n = Number(value);
    switch (key) {
      case "amountPaise":
        anyKnown = true;
        parts.push(Number.isFinite(n) ? `Amount ${paiseToINR(n)}` : token);
        break;
      case "loanId":
        anyKnown = true;
        parts.push(`Loan #${value}`);
        break;
      case "salaryCreditDay":
        anyKnown = true;
        parts.push(`Salary day ${value}`);
        break;
      case "projectedRepaymentDate":
        anyKnown = true;
        parts.push(`Repayment date ${formatDate(value)}`);
        break;
      case "executiveId":
      case "newExecutiveId":
        anyKnown = true;
        parts.push(`To executive #${value}`);
        break;
      case "previousExecutiveId":
        anyKnown = true;
        parts.push(`From executive #${value}`);
        break;
      default:
        parts.push(token);
    }
  }
  // Plain prose ("Pre-approved returning borrower", "Txn/ref: …") has no key=value tokens: leave
  // the sentence exactly as written rather than re-joining its words with dots.
  const head = anyKnown ? parts.join(" · ") : machine.trim();
  return [head, remark].filter(Boolean).join(" · ");
}
