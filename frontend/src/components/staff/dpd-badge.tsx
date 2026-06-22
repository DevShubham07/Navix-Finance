import { Badge } from "@/components/ui/badge";

// DPD bucket badge for collections queues (late penalty 2%/day, cap 30 days).
export interface DpdBadgeProps {
  /** Days past due. */
  dpd: number;
}

function bucket(dpd: number): { label: string; variant: "success" | "warning" | "error" } {
  if (dpd <= 0) return { label: "Current", variant: "success" };
  if (dpd <= 7) return { label: `${dpd} DPD`, variant: "warning" };
  return { label: `${dpd} DPD`, variant: "error" };
}

export function DpdBadge({ dpd }: DpdBadgeProps) {
  const { label, variant } = bucket(dpd);
  return <Badge variant={variant}>{label}</Badge>;
}
