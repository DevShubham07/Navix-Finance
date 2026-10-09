"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Dialog, DialogHeader, DialogTitle, ErrorState, Skeleton, toast } from "@/components/ui";
import { dashboardApi, rupeesToPaise, type DashTarget } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { fmtMonth } from "./fmt";

/** yyyy-MM, `offset` months from now. */
function monthKey(offset: number): string {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() + offset);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

const FROM_OFFSET = -5;
const TO_OFFSET = 6;

function TargetRow({ t, onSaved }: { t: DashTarget; onSaved: () => void }) {
  const [rupees, setRupees] = React.useState(t.disbursalTargetPaise == null ? "" : String(t.disbursalTargetPaise / 100));
  const [pct, setPct] = React.useState(String(t.collectionTargetBp / 100));

  const save = useMutation({
    mutationFn: () => {
      const r = rupees.trim() === "" ? null : Number(rupees);
      const p = Number(pct);
      if (r != null && (!Number.isFinite(r) || r < 0)) throw new Error("Disbursal target must be zero or more.");
      if (!Number.isFinite(p) || p < 0 || p > 100) throw new Error("Collection target must be between 0 and 100%.");
      return dashboardApi.putTarget(t.month, {
        disbursalTargetPaise: r == null ? null : rupeesToPaise(r),
        collectionTargetBp: Math.round(p * 100),
      });
    },
    onSuccess: () => {
      toast.success(`Saved ${fmtMonth(t.month)} targets`);
      onSaved();
    },
    onError: (e) => toast.error(formatApiError(e, "Could not save the target.")),
  });

  return (
    <tr className="border-b border-line">
      <td className="px-2 py-2 text-sm font-semibold text-navy">{fmtMonth(t.month)}</td>
      <td className="px-2 py-2">
        <input
          type="number"
          min={0}
          inputMode="decimal"
          value={rupees}
          onChange={(e) => setRupees(e.target.value)}
          placeholder="Not set"
          aria-label={`${fmtMonth(t.month)} disbursal target in rupees`}
          className="w-full rounded border border-line px-2 py-1 text-xs"
        />
      </td>
      <td className="px-2 py-2">
        <input
          type="number"
          min={0}
          max={100}
          step="0.5"
          value={pct}
          onChange={(e) => setPct(e.target.value)}
          aria-label={`${fmtMonth(t.month)} collection target percent`}
          className="w-20 rounded border border-line px-2 py-1 text-xs"
        />
      </td>
      <td className="px-2 py-2 text-right">
        <button
          type="button"
          onClick={() => save.mutate()}
          disabled={save.isPending}
          className="rounded bg-navy px-3 py-1 text-xs font-semibold text-white disabled:opacity-50"
        >
          {save.isPending ? "Saving…" : "Save"}
        </button>
      </td>
    </tr>
  );
}

/** Real ADMIN only: edit the monthly disbursal target (₹) and collection-% target. */
export function TargetsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const from = monthKey(FROM_OFFSET);
  const to = monthKey(TO_OFFSET);
  const query = useQuery({
    queryKey: ["staff-dashboard-targets", from, to],
    queryFn: () => dashboardApi.targets("ADMIN", from, to),
    enabled: open,
  });

  const saved = () => {
    void qc.invalidateQueries({ queryKey: ["staff-dashboard-targets"] });
    void qc.invalidateQueries({ queryKey: ["staff-dashboard-monthly"] });
  };

  return (
    <Dialog open={open} onClose={onClose} size="lg" aria-labelledby="targets-dialog-title">
      <DialogHeader>
        <DialogTitle id="targets-dialog-title">Monthly targets</DialogTitle>
        <p className="m-0 text-xs text-muted">
          Disbursal target in rupees and the collection-% target the deficit is measured against (default 88%).
        </p>
      </DialogHeader>
      <div className="max-h-[60vh] overflow-y-auto">
        {query.isLoading ? (
          <Skeleton variant="table" rows={6} cols={4} />
        ) : query.isError ? (
          <ErrorState error={query.error} onRetry={() => void query.refetch()} />
        ) : (
          <table className="w-full text-left">
            <thead>
              <tr className="text-xs text-muted">
                <th className="px-2 py-1 font-semibold">Month</th>
                <th className="px-2 py-1 font-semibold">Disbursal target (₹)</th>
                <th className="px-2 py-1 font-semibold">Collection %</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {(query.data ?? []).map((t) => (
                <TargetRow key={`${t.month}-${t.disbursalTargetPaise}-${t.collectionTargetBp}`} t={t} onSaved={saved} />
              ))}
            </tbody>
          </table>
        )}
      </div>
      <div className="mt-3 flex justify-end">
        <button type="button" onClick={onClose} className="btn btn-sm btn-outline">
          Close
        </button>
      </div>
    </Dialog>
  );
}
