"use client";

import * as React from "react";
import { useQueryClient } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { MandatesView } from "@/components/staff/mandates/mandates-view";
import { DebitsView } from "@/components/staff/mandates/debits-view";
import { cn } from "@/lib/utils";

const TABS = [
  { id: "mandates", label: "Mandates" },
  { id: "debits", label: "Debits" },
] as const;

/**
 * Mandates & debits (COLLECTION_HEAD / ADMIN). Read-only lists; every action — check status, cancel a
 * debit, cancel or re-invite a mandate, debit now, the limit override — opens the customer's existing
 * Mandate tab, so the rules and permissions are the same ones staff already use there.
 */
export default function MandatesPage() {
  const [tab, setTab] = React.useState<(typeof TABS)[number]["id"]>("mandates");
  const [appId, setAppId] = React.useState<number | null>(null);
  const qc = useQueryClient();

  const close = () => {
    setAppId(null);
    // An action in the dialog may have changed a mandate or debit; refresh the console's lists.
    for (const key of ["mandate-summary", "mandate-list", "mandate-debits", "mandate-debits-all"]) {
      void qc.invalidateQueries({ queryKey: [key] });
    }
  };

  return (
    <div className="space-y-5">
      <PageHeader title="Mandates & debits" subtitle="Every eMandate and every auto-debit, with who did what" />
      <div role="tablist" aria-label="Mandates and debits" className="flex w-fit gap-1 rounded-xl bg-navy p-1.5">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => setTab(t.id)}
            className={cn(
              "rounded-lg px-4 py-1.5 text-xs font-semibold transition",
              tab === t.id ? "bg-white text-navy shadow" : "text-white/80 hover:bg-white/10 hover:text-white",
            )}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab === "mandates" ? <MandatesView onOpenActions={setAppId} /> : <DebitsView onOpenActions={setAppId} />}
      {appId != null && <ApplicationDetailDialog applicationId={appId} initialTab="mandate" onClose={close} />}
    </div>
  );
}
