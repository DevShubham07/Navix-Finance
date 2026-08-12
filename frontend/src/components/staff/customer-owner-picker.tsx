"use client";

import * as React from "react";
import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { Check, Loader2 } from "lucide-react";
import { Select } from "@/components/ui";
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

  React.useEffect(() => {
    setStaffId(ownerStaffId != null ? String(ownerStaffId) : "");
  }, [ownerStaffId, customerId]);

  const staffQueries = useQueries({
    queries: roles.map((role) => ({
      queryKey: ["staff-picker", role],
      queryFn: () => staffApi.creditExecutives(role),
      staleTime: 60_000,
    })),
  });
  const options = React.useMemo(() => {
    const byId = new Map<number, StaffSummary>();
    for (const query of staffQueries) {
      for (const staff of query.data ?? []) byId.set(staff.id, staff);
    }
    return Array.from(byId.values()).sort((a, b) => a.name.localeCompare(b.name));
  }, [staffQueries]);

  const assign = useMutation({
    mutationFn: () => customersApi.assignOwner(customerId, staffId ? Number(staffId) : null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["customer", customerId] });
      qc.invalidateQueries({ queryKey: ["customer-detail", customerId] });
      qc.invalidateQueries({ queryKey: ["customers"] });
      qc.invalidateQueries({ queryKey: ["customer-activity", customerId] });
      qc.invalidateQueries({ queryKey: ["staff-telecalling"] });
      onChanged?.();
    },
  });

  const controls = (
    <PermissionGate permission="customer:assign">
      <div className={compact ? "flex min-w-[15rem] items-end gap-2" : "mt-2 space-y-2"}>
        <Select
          label="Assign to"
          value={staffId}
          onChange={(event) => setStaffId(event.target.value)}
          options={[
            { value: "", label: "Unallocated" },
            ...options.map((staff) => ({
              value: String(staff.id),
              label: `${staff.name} (${staff.role})`,
            })),
          ]}
          className="!mb-0"
        />
        <button
          type="button"
          onClick={() => assign.mutate()}
          disabled={assign.isPending}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {assign.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />}
          Save
        </button>
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
