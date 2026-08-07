import { AlertTriangle } from "lucide-react";
import { PAYMENT_SAFETY_NOTICE } from "@/lib/brand";

/**
 * Rotating anti-fraud notice (revamp.md decision 49). The message is duplicated so the marquee
 * loops seamlessly; the copy is `aria-hidden` so screen readers announce it once. Motion is off
 * under `prefers-reduced-motion`, where it degrades to plain wrapped text.
 */
export function PaymentSafetyTicker({ className = "" }: { className?: string }) {
  return (
    <div
      className={`overflow-hidden border-y border-gold-soft bg-gold-50/70 py-2.5 text-sm text-navy ${className}`}
      role="note"
    >
      <div className="flex items-center gap-2 px-4 motion-safe:hidden">
        <AlertTriangle size={16} className="flex-shrink-0 text-gold-dark" />
        <span>{PAYMENT_SAFETY_NOTICE}</span>
      </div>

      <div className="hidden w-max whitespace-nowrap motion-safe:flex motion-safe:animate-ticker">
        {[0, 1].map((i) => (
          <span key={i} className="flex items-center gap-2 pr-16" aria-hidden={i === 1}>
            <AlertTriangle size={16} className="flex-shrink-0 text-gold-dark" />
            {PAYMENT_SAFETY_NOTICE}
          </span>
        ))}
      </div>
    </div>
  );
}
