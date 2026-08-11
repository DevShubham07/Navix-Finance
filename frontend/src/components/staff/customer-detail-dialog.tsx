"use client";

/**
 * Customer-first entry point into the **one** staff detail modal.
 *
 * There used to be two modals — this one (customer tabs) and {@link ApplicationDetailDialog}
 * (application tabs + the stage actions) — and which fields you got depended on which row you
 * happened to click. They are now a single dialog carrying the union of both tab sets, so this
 * component's whole job is to resolve "customer #N" to their latest application and hand over.
 *
 * A customer with no application at all (a lead that never applied) can't open the application
 * dialog, so they fall back to the customer tabs on their own — the one case where the two
 * surfaces still differ, because there is genuinely no application to show.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, X, ExternalLink } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { Tabs } from "@/components/ui/tabs";
import { CUSTOMER_TABS, CustomerTabBody } from "@/components/staff/customer-tabs";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { customersApi } from "@/lib/api/applications";

export function CustomerDetailDialog({
  customerId,
  onClose,
}: {
  customerId: number | null;
  onClose: () => void;
}) {
  const [tab, setTab] = React.useState("personal");
  const open = customerId != null;

  const detailQ = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId as number),
    enabled: open,
  });

  const c = detailQ.data;
  const latestApplicationId = c?.applications?.[0]?.id ?? null;

  // The normal path: hand straight over to the unified modal, opened on this customer's most
  // recent application. It carries every customer tab too, so nothing is lost.
  if (open && latestApplicationId != null) {
    return <ApplicationDetailDialog applicationId={latestApplicationId} onClose={onClose} />;
  }

  return (
    <Dialog open={open} onClose={onClose} className="!max-w-4xl !w-[min(56rem,94vw)]">
      <div className="flex items-center justify-between gap-3 border-b border-line pb-3">
        <div>
          <h3 className="font-serif text-lg text-navy">
            {c?.profile?.fullName ?? "Customer"}{" "}
            <span className="text-sm font-normal text-muted">#{customerId}</span>
          </h3>
          {c?.profile && (
            <p className="text-xs text-muted">
              {c.profile.mobile ?? "—"} · PAN {c.profile.pan ?? "—"}
              {c.profile.riskCategory ? ` · risk ${c.profile.riskCategory}` : ""}
              {c.ownerName ? ` · owner ${c.ownerName}` : " · Unallocated"}
            </p>
          )}
        </div>
        <button onClick={onClose} className="rounded p-1 text-muted hover:bg-grey-100 hover:text-ink" aria-label="Close">
          <X size={18} />
        </button>
      </div>

      <Tabs tabs={CUSTOMER_TABS} active={tab} onChange={setTab} className="mt-2" />

      <div className="mt-3 max-h-[68vh] overflow-y-auto pr-1 text-[10.4px]">
        {detailQ.isLoading ? (
          <p className="flex items-center gap-2 py-8 text-sm text-muted">
            <Loader2 size={15} className="animate-spin" /> Loading…
          </p>
        ) : detailQ.error ? (
          <p className="py-8 text-sm text-error-700">Could not load this customer.</p>
        ) : !c || customerId == null ? null : (
          <>
            <CustomerTabBody
              tab={tab}
              detail={c}
              customerId={customerId}
              onChanged={() => detailQ.refetch()}
            />
            {tab === "personal" && (
              <p className="mt-4 text-sm text-muted">
                <a
                  href={`/staff/customers/${customerId}`}
                  className="inline-flex items-center gap-1 font-semibold text-navy hover:underline"
                >
                  Open full customer page <ExternalLink size={13} />
                </a>
              </p>
            )}
          </>
        )}
      </div>
    </Dialog>
  );
}
