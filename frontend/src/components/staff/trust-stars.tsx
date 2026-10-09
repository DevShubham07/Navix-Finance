import { Star } from "lucide-react";
import type { TrustSignals, TrustStar } from "@/lib/api/applications";
import { cn } from "@/lib/utils";

const TIPS: Record<keyof TrustSignals, Record<TrustStar, string>> = {
  bureau: {
    PASS: "Bureau: no DPD in last 6 months",
    FAIL: "Bureau: DPD in last 6 months",
    NOT_CHECKED: "Bureau: no report yet",
  },
  uan: { PASS: "UAN verified", FAIL: "UAN not verified", NOT_CHECKED: "UAN not checked" },
  email: {
    PASS: "Work email verified",
    FAIL: "Work email not verified",
    NOT_CHECKED: "Work email not checked",
  },
};

// Colours avoid the black-text rule in globals.css (text-warning-* and text-ink/NN are not matched).
const LOOK: Record<TrustStar, string> = {
  PASS: "text-warning-500 fill-current",
  FAIL: "text-ink/70",
  NOT_CHECKED: "text-ink/25 [stroke-linecap:butt] [stroke-dasharray:3_3]",
};

/** Three tiny stars beside a customer: bureau clean · UAN verified · work email verified. */
export function TrustStars({ trust, size = 12 }: { trust?: TrustSignals | null; size?: number }) {
  const keys = ["bureau", "uan", "email"] as const;
  const tips = keys.map((k) => TIPS[k][trust?.[k] ?? "NOT_CHECKED"]);
  return (
    <span role="img" aria-label={tips.join("; ")} className="inline-flex items-center gap-px">
      {keys.map((k, i) => {
        const s = trust?.[k] ?? "NOT_CHECKED";
        return (
          <span key={k} title={tips[i]} data-star={s}>
            <Star aria-hidden width={size} height={size} className={cn(LOOK[s])} />
          </span>
        );
      })}
    </span>
  );
}
