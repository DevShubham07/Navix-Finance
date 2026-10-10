"use client";

import * as React from "react";
import { Banknote, Calculator, ClipboardCheck, HandCoins, Headset, ShieldCheck, UserCheck, Users } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { DashView } from "@/lib/api/applications";
import { cn } from "@/lib/utils";
import { ROLE_TONE, TONE_TEXT } from "./colors";
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
 * Dashboard-local view toggle in the kit's `.nav-pill` look: white pills, the active one filled ink.
 * An inactive pill's icon keeps its domain colour (Admin ink, Credit green, Collection red, Telecaller
 * ember, Disbursement violet, Accountant sky).
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
    <div role="group" aria-label="Dashboard view" className="-mx-1 mb-4 flex gap-2 overflow-x-auto px-1 pb-2 pt-1">
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
            className={cn("nav-pill shrink-0", on && "is-active")}
          >
            <Icon size={14} aria-hidden style={on ? undefined : { color: TONE_TEXT[tone] }} />
            {VIEW_LABELS[v]}
          </button>
        );
      })}
    </div>
  );
}
