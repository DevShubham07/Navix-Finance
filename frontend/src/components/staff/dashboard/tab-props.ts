import type { DashParams } from "@/lib/api/applications";
import type { RecordsTarget } from "./records-drawer";

/** What every dashboard tab / role view receives from the page shell. */
export interface DashTabProps {
  /** view + from + to (+ staffIds) the whole page is showing. */
  params: DashParams;
  /** Open the records drawer for a clicked number. */
  open: (t: RecordsTarget) => void;
  /** The session's REAL role is ADMIN (gates "Edit targets"). */
  realAdmin: boolean;
  /** e.g. "01 Oct 2026 - 09 Oct 2026". */
  periodLabel: string;
}
