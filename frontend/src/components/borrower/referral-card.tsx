"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Gift, Copy, Check } from "lucide-react";
import { referralApi } from "@/lib/api/applications";
import { formatINR0 } from "@/lib/utils";

/**
 * Borrower "Refer & earn" card for the dashboard aside. Reads the caller's own referral code +
 * reward + earnings via `referralApi.me()` (the code is minted lazily server-side). Renders nothing
 * when the program is disabled (config-driven) or while loading.
 */
export function ReferralCard() {
  const q = useQuery({ queryKey: ["referral-me"], queryFn: referralApi.me });
  const [copied, setCopied] = React.useState(false);
  const data = q.data;
  if (!data || !data.enabled) return null;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(data.code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable — the code is shown above to copy manually */
    }
  };

  const share = async () => {
    const nav = typeof navigator !== "undefined" ? navigator : undefined;
    if (nav && typeof nav.share === "function") {
      try {
        await nav.share({ title: "DhanBoost referral", text: data.shareMessage });
        return;
      } catch {
        /* user dismissed the share sheet — fall through to copy */
      }
    }
    void copy();
  };

  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-1 flex items-center gap-2 text-sm font-semibold text-navy">
        <Gift size={16} /> Refer &amp; earn ₹{data.rewardRupees}
      </div>
      <p className="mb-3 text-xs text-muted">
        You and your friend each get ₹{data.rewardRupees} once their first loan is disbursed.
      </p>

      <div className="flex items-center justify-between gap-2 rounded border border-dashed border-line bg-grey-100 px-3 py-2">
        <span className="font-mono text-lg font-bold tracking-widest text-navy">{data.code}</span>
        <button
          type="button"
          onClick={copy}
          className="inline-flex items-center gap-1 text-xs font-semibold text-gold-dark hover:underline"
        >
          {copied ? (
            <>
              <Check size={14} /> Copied
            </>
          ) : (
            <>
              <Copy size={14} /> Copy
            </>
          )}
        </button>
      </div>

      <button type="button" onClick={share} className="btn btn-outline btn-sm mt-3 w-full">
        Share your code
      </button>

      <div className="mt-3 grid grid-cols-2 gap-2 text-center">
        <div className="rounded bg-grey-100 px-2 py-2">
          <div className="font-serif text-lg font-bold text-navy">{formatINR0(data.totalEarnedPaise / 100)}</div>
          <div className="text-[11px] text-muted">Earned</div>
        </div>
        <div className="rounded bg-grey-100 px-2 py-2">
          <div className="font-serif text-lg font-bold text-navy">{data.referredQualifiedCount}</div>
          <div className="text-[11px] text-muted">Friends joined</div>
        </div>
      </div>
      {data.pendingPaise > 0 ? (
        <p className="mt-2 text-center text-[11px] text-muted">
          {formatINR0(data.pendingPaise / 100)} pending payout
        </p>
      ) : null}
    </div>
  );
}
