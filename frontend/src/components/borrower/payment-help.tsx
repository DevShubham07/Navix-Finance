import * as React from "react";
import { Phone, Mail } from "lucide-react";
import { BRAND } from "@/lib/brand";
import { cn } from "@/lib/utils";

const MAILTO_HREF = `mailto:${BRAND.email}?subject=${encodeURIComponent("Payment issue — DhanBoost")}`;

/**
 * Payment-issue help affordance — reads everything from `BRAND` so item 6 (support contact) stays
 * the single source of truth. Two render shapes:
 * - `variant="card"` (default): a standalone bordered card, for a page like /repay or the dashboard.
 * - `variant="inline"`: a compact one-line strip meant to sit inside another banner (e.g. the
 *   rejected-payment banner) — no border/shadow of its own.
 */
export function PaymentHelp({
  variant = "card",
  compact = false,
  className,
}: {
  variant?: "card" | "inline";
  /** Tightens spacing/typography further — useful when nested inside an already-dense banner. */
  compact?: boolean;
  className?: string;
}) {
  const links = (
    <div className={cn("flex flex-wrap items-center gap-x-4 gap-y-1", compact ? "text-xs" : "text-sm")}>
      <a
        href={BRAND.phoneHref}
        className="inline-flex items-center gap-1.5 font-semibold text-navy hover:text-navy-700"
      >
        <Phone size={compact ? 13 : 14} className="flex-shrink-0" />
        Call {BRAND.phone}
      </a>
      <a
        href={MAILTO_HREF}
        className="inline-flex items-center gap-1.5 font-semibold text-navy hover:text-navy-700"
      >
        <Mail size={compact ? 13 : 14} className="flex-shrink-0" />
        Email {BRAND.email}
      </a>
    </div>
  );

  if (variant === "inline") {
    return (
      <div className={cn("flex flex-wrap items-center gap-x-2 gap-y-1", className)}>
        <span className={cn("font-semibold text-ink", compact ? "text-xs" : "text-sm")}>
          Payment issue?
        </span>
        {links}
      </div>
    );
  }

  return (
    <div
      className={cn(
        "rounded border border-line bg-white shadow-sm",
        compact ? "p-4" : "p-6",
        className,
      )}
    >
      <h3 className={cn("font-serif text-navy", compact ? "mb-1 text-sm" : "mb-2 text-base")}>
        Payment issue?
      </h3>
      <p className={cn("mb-3 text-muted", compact ? "text-xs" : "text-sm")}>
        If a payment isn&apos;t reflecting or something looks wrong, reach our support team directly.
      </p>
      {links}
    </div>
  );
}
