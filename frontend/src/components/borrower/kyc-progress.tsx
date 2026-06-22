import * as React from "react";
import { Check, Clock, Loader2, X } from "lucide-react";
import type { KycCheck, KycState } from "@/lib/mock/borrower";
import { cn } from "@/lib/utils";

const META: Record<KycCheck, { label: string; cls: string; Icon: React.ElementType }> = {
  VERIFIED: { label: "Verified", cls: "bg-success-50 text-success-700", Icon: Check },
  IN_PROGRESS: { label: "In progress", cls: "bg-warning-50 text-warning-700", Icon: Loader2 },
  PENDING: { label: "Pending", cls: "bg-grey-100 text-muted", Icon: Clock },
  FAILED: { label: "Failed", cls: "bg-error-50 text-error-700", Icon: X },
};

const ROWS: Array<{ key: keyof KycState; label: string; sub: string }> = [
  { key: "pan", label: "PAN", sub: "Identity & name match" },
  { key: "aadhaar", label: "Aadhaar (DigiLocker)", sub: "Government e-KYC" },
  { key: "selfie", label: "Liveness selfie", sub: "Face match to PAN photo" },
  { key: "address", label: "Address proof", sub: "Current residence" },
  { key: "bank", label: "Salary bank", sub: "Penny-drop name match" },
];

/** Vertical list of KYC checks with live per-check status. */
export function KycProgress({ kyc, className }: { kyc: KycState; className?: string }) {
  return (
    <ul className={cn("divide-y divide-grey-200 rounded border border-line bg-white", className)}>
      {ROWS.map(({ key, label, sub }) => {
        const status = kyc[key];
        const m = META[status];
        return (
          <li key={key} className="flex items-center justify-between gap-3 px-4 py-3">
            <div>
              <div className="text-sm font-semibold text-ink">{label}</div>
              <div className="text-xs text-muted">{sub}</div>
            </div>
            <span className={cn("inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold", m.cls)}>
              <m.Icon size={13} className={status === "IN_PROGRESS" ? "animate-spin" : undefined} />
              {m.label}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
