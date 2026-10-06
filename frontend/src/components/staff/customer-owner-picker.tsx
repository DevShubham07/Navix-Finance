"use client";

import * as React from "react";
import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { Check, Loader2 } from "lucide-react";
import { Select, toast } from "@/components/ui";
import { PermissionGate, errMessage } from "@/components/staff/live-pipeline";
import { Section, KV } from "@/components/staff/detail-parts";
import { useStaffSession } from "@/lib/auth/staff-session";
import { customersApi, staffApi, type StaffSummary } from "@/lib/api/applications";

const OWNER_ROLES = ["CREDIT_EXECUTIVE", "COLLECTION_EXECUTIVE", "TELECALLER"] as const;

export function CustomerOwnerPicker({
  customerId,
  ownerStaffId,
  ownerName,
  onChanged,
  allowedRoles,
  compact = false,
}: {
  customerId: number;
  ownerStaffId?: number | null;
  ownerName?: string | null;
  onChanged?: () => void;
  allowedRoles?: readonly string[];
  compact?: boolean;
}) {
  const qc = useQueryClient();
  const actorRole = useStaffSession().session?.role;
  const roles = React.useMemo(
    () => allowedRoles ?? (actorRole === "TELECALLER" ? ["TELECALLER"] : OWNER_ROLES),
    [actorRole, allowedRoles],
  );
  const [staffId, setStaffId] = React.useState(ownerStaffId != null ? String(ownerStaffId) : "");
  /**
   * The roster is fetched on the first open of the picker (pointer over it, press or focus), not on
   * mount — most readers of a customer never reassign. The one exception: a caller that passes an
   * owner id without a name (the telecalling rows) still needs the roster to label the current owner,
   * so that case loads as before. The key is unchanged, so every picker shares one cached roster
   * and a disabled query still reads whatever is already in the cache.
   */
  const [opened, setOpened] = React.useState(false);
  const markOpened = React.useCallback(() => setOpened(true), []);
  const needsRosterForLabel = ownerStaffId != null && !ownerName;

  React.useEffect(() => {
    setStaffId(ownerStaffId != null ? String(ownerStaffId) : "");
  }, [ownerStaffId, customerId]);

  const staffQueries = useQueries({
    queries: roles.map((role) => ({
      queryKey: ["staff-picker", role],
      queryFn: () => staffApi.creditExecutives(role),
      staleTime: 60_000,
      enabled: opened || needsRosterForLabel,
    })),
  });
  const rosterLoading = staffQueries.some((query) => query.isFetching && query.data == null);
  const rosterFailed = staffQueries.some((query) => query.isError);
  const options = React.useMemo(() => {
    const byId = new Map<number, StaffSummary>();
    for (const query of staffQueries) {
      for (const staff of query.data ?? []) byId.set(staff.id, staff);
    }
    return Array.from(byId.values()).sort((a, b) => a.name.localeCompare(b.name));
  }, [staffQueries]);
  // Until the roster arrives, the current owner still needs an <option>, or the select would
  // silently show "Unallocated" for an owned customer.
  const ownerMissing =
    ownerStaffId != null && !options.some((staff) => staff.id === ownerStaffId);

  const assign = useMutation({
    mutationFn: () => customersApi.assignOwner(customerId, staffId ? Number(staffId) : null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["customer", customerId] });
      qc.invalidateQueries({ queryKey: ["customer-detail", customerId] });
      // The customers register reads ["customers-page", …] / ["customers-summary", …];
      // React Query prefix-matches element-by-element, so a bare ["customers"] matches neither.
      qc.invalidateQueries({ queryKey: ["customers-page"] });
      qc.invalidateQueries({ queryKey: ["customers-summary"] });
      qc.invalidateQueries({ queryKey: ["customer-activity", customerId] });
      qc.invalidateQueries({ queryKey: ["staff-telecalling"] });
      onChanged?.();
      toast.success("Owner updated");
    },
  });

  const controls = (
    <PermissionGate permission="customer:assign">
      <div className={compact ? "flex min-w-[15rem] items-end gap-2" : "mt-2 space-y-2"}>
        <Select
          label="Assign to"
          value={staffId}
          onChange={(event) => setStaffId(event.target.value)}
          onPointerEnter={markOpened}
          onPointerDown={markOpened}
          onFocus={markOpened}
          aria-busy={rosterLoading || undefined}
          className="!mb-0"
        >
          <option value="">Unallocated</option>
          {ownerMissing && (
            <option value={String(ownerStaffId)}>{ownerName ?? `Staff #${ownerStaffId}`}</option>
          )}
          {options.map((staff) => (
            <option key={staff.id} value={String(staff.id)}>
              {staff.name} ({staff.role})
            </option>
          ))}
          {rosterLoading && (
            <option value="__loading" disabled>
              Loading staff…
            </option>
          )}
        </Select>
        <button
          type="button"
          onClick={() => assign.mutate()}
          disabled={assign.isPending}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {assign.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />}
          Save
        </button>
        {rosterFailed && <p className="text-xs text-error-700">Could not load the staff list.</p>}
        {assign.error && <p className="text-xs text-error-700">{errMessage(assign.error)}</p>}
      </div>
    </PermissionGate>
  );

  if (compact) return controls;
  return (
    <Section title="Owner">
      <KV k="Current owner" v={ownerName ?? "Unallocated"} />
      {controls}
    </Section>
  );
}
