import { ShieldOff } from "lucide-react";
import { EmptyState } from "@/components/ui";

/** Disabled in the tab strip; this body only shows if something deep-links to it. No setup button. */
export function MandateTab() {
  return (
    <EmptyState
      icon={<ShieldOff size={28} />}
      title="UPI Autopay / NACH mandates are not available yet."
      hint="Repayment is manual."
    />
  );
}
