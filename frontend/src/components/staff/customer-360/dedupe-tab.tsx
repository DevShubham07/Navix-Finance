"use client";

import { useQuery } from "@tanstack/react-query";
import { Ban, Copy, ShieldAlert } from "lucide-react";
import { Badge, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { Section } from "@/components/staff/detail-parts";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { useStaffSession } from "@/lib/auth/staff-session";
import { customersApi } from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";
import { can as rbacCan, type Permission } from "@/lib/auth/rbac";

const TYPE_LABEL: Record<string, string> = {
  PAN: "PAN",
  PHONE: "Phone",
  AADHAAR_REF: "Aadhaar ref",
  BANK_ACCOUNT: "Bank account",
  DEVICE: "Device",
};

export function DedupeTab({ customerId, onTabChange }: TabCtx) {
  const sess = useStaffSession().session;
  const role = sess?.role;
  const can = (p: Permission) => rbacCan(sess?.realRole, role, p);
  const q = useQuery({
    queryKey: ["customer-dedupe", customerId],
    queryFn: () => customersApi.dedupe(customerId),
  });
  if (q.isLoading) return <Skeleton variant="line" rows={6} />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;

  const { aadhaar, blocklistHits, rejection } = q.data;
  const clear = aadhaar.status == null;
  const passed = aadhaar.status === "PASS";
  const showAdded = blocklistHits.some((h) => h.addedOn);

  return (
    <div className="space-y-3">
      <Section
        title="Aadhaar duplicate"
        tone={clear || passed ? "neutral" : aadhaar.status === "FAIL" ? "error" : "warning"}
        icon={Copy}
        pill={
          <Badge variant={clear || passed ? "success" : aadhaar.status === "FAIL" ? "error" : "warning"} size="sm">
            {clear ? "Clear" : aadhaar.status}
          </Badge>
        }
        action={
          !clear && !passed && onTabChange && role != null && can("kyc:approve") ? (
            <button type="button" className="btn btn-sm btn-outline" onClick={() => onTabChange("verifications")}>
              Override
            </button>
          ) : undefined
        }
      >
        {clear ? (
          <p className="text-sm text-muted">No other customer holds this Aadhaar number.</p>
        ) : passed ? (
          <p className="text-sm text-ink">{aadhaar.message ?? "Reviewed and cleared."}</p>
        ) : (
          <div className="space-y-1 text-sm text-ink">
            <p>{aadhaar.message ?? "Another customer already holds this Aadhaar number."}</p>
            {aadhaar.otherCustomerIds.length > 0 && (
              <p className="text-xs text-muted">
                Other customers:{" "}
                {aadhaar.otherCustomerIds.map((id, i) => (
                  <span key={id}>
                    {i > 0 && ", "}
                    <a className="font-medium text-navy underline" href={`/staff/customers/${id}`}>
                      #{id}
                    </a>
                  </span>
                ))}
              </p>
            )}
            <p className="text-xs text-muted">Clear it with the manual override on the Verifications tab.</p>
          </div>
        )}
      </Section>

      <Section title="Blocklist hits" icon={ShieldAlert} tone={blocklistHits.length > 0 ? "error" : "neutral"}>
        {blocklistHits.length === 0 ? (
          <EmptyState title="No blocklist match" className="py-4" />
        ) : (
          <table className="staff-data-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Value</th>
                <th>Reason</th>
                {showAdded && <th>Added on</th>}
              </tr>
            </thead>
            <tbody>
              {blocklistHits.map((h) => (
                <tr key={`${h.type}-${h.maskedValue}`} className="bg-error-50">
                  <td>{TYPE_LABEL[h.type] ?? h.type}</td>
                  <td className="font-mono">{h.maskedValue}</td>
                  <td>{h.reason ?? "—"}</td>
                  {showAdded && <td>{h.addedOn ? formatDate(h.addedOn) : "—"}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      <Section
        title="Rejection register"
        tone={rejection ? "error" : "neutral"}
        icon={Ban}
        pill={
          <Badge variant={rejection ? "error" : "success"} size="sm">
            {rejection ? "Blocked" : "Not blocked"}
          </Badge>
        }
      >
        {rejection ? (
          <p className="text-sm text-ink">
            Blocked until {rejection.blockedUntil ? formatDate(rejection.blockedUntil) : "—"}
            {" · "}
            {rejection.reasonDetail ?? rejection.reasonCode}
          </p>
        ) : (
          <p className="text-sm text-muted">No active cooling-off block on this mobile.</p>
        )}
      </Section>
    </div>
  );
}
