import * as React from "react";
import { Badge } from "@/components/ui/badge";

// TODO: refine DPD bucket thresholds with collections policy (late penalty 2%/day, cap 30 days).
export interface DpdBadgeProps {
  /** Days past due. */
  dpd: number;
}

function bucket(dpd: number): { label: string; variant: "success" | "warning" | "danger" } {
  if (dpd <= 0) return { label: "Current", variant: "success" };
  if (dpd <= 7) return { label: `${dpd} DPD`, variant: "warning" };
  return { label: `${dpd} DPD`, variant: "danger" };
}

export function DpdBadge({ dpd }: DpdBadgeProps) {
  const { label, variant } = bucket(dpd);
  return <Badge variant={variant}>{label}</Badge>;
}
