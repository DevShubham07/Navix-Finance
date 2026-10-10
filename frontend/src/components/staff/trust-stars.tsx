import { Star } from "lucide-react";
import type { TrustSignals, TrustStar } from "@/lib/api/applications";
import { InfoTooltip } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

const TIPS: Record<"bureau" | "uan" | "email", Record<TrustStar, string>> = {
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

const LABELS = { bureau: "Credit bureau", uan: "UAN / EPFO", email: "Work email" } as const;
const VERDICT: Record<TrustStar, { word: string; cls: string }> = {
  PASS: { word: "Passed", cls: "text-success-700" },
  FAIL: { word: "Failed", cls: "text-error-700" },
  NOT_CHECKED: { word: "Not checked", cls: "text-ink/60" },
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
  const state = (k: (typeof keys)[number]) => trust?.[k] ?? "NOT_CHECKED";
  const tips = keys.map((k) => TIPS[k][state(k)]);
  const why = { bureau: trust?.bureauWhy, uan: trust?.uanWhy, email: trust?.emailWhy };
  const content = (
    <span className="flex flex-col gap-2">
      {keys.map((k) => {
        const s = state(k);
        return (
          <span key={k} className="block">
            <span className="flex items-center gap-1">
              <Star aria-hidden width={11} height={11} className={cn(LOOK[s])} />
              <b className="font-semibold text-black">{LABELS[k]}</b>
              <span className={cn("font-medium", VERDICT[s].cls)}>{VERDICT[s].word}</span>
            </span>
            <span className="mt-0.5 block text-ink/70">{why[k] || TIPS[k][s]}</span>
          </span>
        );
      })}
    </span>
  );
  return (
    <span className="inline-flex items-center">
      <InfoTooltip content={content} label={`Trust signals: ${tips.join("; ")}`}>
        <span className="inline-flex items-center gap-px">
          {keys.map((k) => (
            <span key={k} data-star={state(k)}>
              <Star aria-hidden width={size} height={size} className={cn(LOOK[state(k)])} />
            </span>
          ))}
        </span>
      </InfoTooltip>
    </span>
  );
}
