"use client";

import * as React from "react";
import { Banknote, Calculator, ClipboardCheck, HandCoins, Headset, ShieldCheck, UserCheck, Users } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { DashView } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { GRADIENTS, ROLE_TONE, TONE_TINT } from "./colors";
import { VIEW_LABELS } from "./views";

const ICONS: Record<DashView, LucideIcon> = {
  ADMIN: ShieldCheck,
  CREDIT_HEAD: ClipboardCheck,
  CREDIT_EXECUTIVE: UserCheck,
  COLLECTION_HEAD: HandCoins,
  COLLECTION_EXECUTIVE: Users,
  TELECALLER: Headset,
  DISBURSEMENT_HEAD: Banknote,
  ACCOUNTANT: Calculator,
};

/**
 * Dashboard-local view toggle. Active pill is filled with its domain colour (Admin navy, Credit
 * emerald, Collection red, Telecaller orange, Disbursement violet, Accountant sky); inactive pills
 * are white with a tinted hover.
 */
export function RoleToggleStrip({
  views,
  active,
  onChange,
}: {
  views: DashView[];
  active: DashView;
  onChange: (v: DashView) => void;
}) {
  if (views.length < 2) return null;
  return (
    <div role="group" aria-label="Dashboard view" className="mb-4 flex gap-2 overflow-x-auto pb-1">
      {views.map((v) => {
        const Icon = ICONS[v];
        const tone = ROLE_TONE[v] ?? "navy";
        const on = v === active;
        return (
          <button
            key={v}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(v)}
            style={
              on
                ? { backgroundImage: `linear-gradient(135deg, ${GRADIENTS[tone][0]}, ${GRADIENTS[tone][1]})` }
                : undefined
            }
            onMouseEnter={(e) => {
              if (!on) e.currentTarget.style.background = TONE_TINT[tone];
            }}
            onMouseLeave={(e) => {
              if (!on) e.currentTarget.style.background = "";
            }}
            className={cn(
              "flex shrink-0 items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-xs font-semibold transition",
              on ? "border-transparent text-white shadow-md" : "border-line bg-white text-ink",
            )}
          >
            <Icon size={14} aria-hidden />
            {VIEW_LABELS[v]}
          </button>
        );
      })}
    </div>
  );
}
