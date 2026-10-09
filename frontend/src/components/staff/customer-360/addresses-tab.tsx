"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Home, IdCard, MapPin, Search } from "lucide-react";
import { Badge, EmptyState, ErrorState, Skeleton } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { Section } from "@/components/staff/detail-parts";
import { providerLine } from "@/components/staff/verification-checks";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { skipTraceApi, staffApi } from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";

const s = (v: unknown): string | null => (v == null || v === "" ? null : String(v));

export function AddressesTab({ detail, customerId, applicationId }: TabCtx) {
  const verQ = useQuery({
    queryKey: ["verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId as number),
    enabled: applicationId != null,
  });
  const traceQ = useQuery({
    queryKey: ["skip-trace", customerId],
    queryFn: () => skipTraceApi.history(customerId),
    retry: false,
  });
  const steps = verQ.data ?? [];
  const aadhaar = (steps.find((x) => x.checkType === "AADHAAR")?.derived ?? {}) as Record<string, unknown>;
  const check = steps.find((x) => x.checkType === "ADDRESS");
  const status = check?.status as string | undefined;
  const checkPill = !check ? (
    <Badge variant="neutral" size="sm">Not run</Badge>
  ) : (
    <Badge variant={status === "PASS" ? "success" : status === "FAIL" ? "error" : "warning"} size="sm">{status}</Badge>
  );
  const addresses = traceQ.data?.find((r) => r.status === "SUCCESS")?.response?.result?.addresses ?? [];

  return (
    <div className="grid gap-3 md:grid-cols-2">
      <Section title="Declared" icon={Home}>
        <FieldGrid cols={2}>
          <Field label="Address" className="col-span-2">{detail.profile?.address}</Field>
        </FieldGrid>
      </Section>

      <Section title="Aadhaar (DigiLocker)" icon={IdCard}>
        {verQ.isLoading ? (
          <Skeleton variant="line" rows={3} />
        ) : (
          <FieldGrid cols={2}>
            <Field label="Address" className="col-span-2">{s(aadhaar.address)}</Field>
            <Field label="Address line">{s(aadhaar.addressLine)}</Field>
            <Field label="Landmark">{s(aadhaar.landmark)}</Field>
            <Field label="City">{s(aadhaar.city)}</Field>
            <Field label="District">{s(aadhaar.district)}</Field>
            <Field label="State">{s(aadhaar.state)}</Field>
            <Field label="PIN" mono>{s(aadhaar.pincode)}</Field>
            <Field label="Country">{s(aadhaar.country)}</Field>
          </FieldGrid>
        )}
      </Section>

      <Section title="Address check" icon={MapPin} pill={checkPill}>
        {check ? (
          <FieldGrid cols={2}>
            <Field label="Provider" caption={check.checkedAt ? formatDateTime(check.checkedAt) : undefined}>
              {providerLine(check)}
            </Field>
            <Field label="Result">{check.message}</Field>
            <Field label="Resolved address" className="col-span-2">
              {s(check.derived?.address ?? check.derived?.manualAddress)}
            </Field>
          </FieldGrid>
        ) : (
          <EmptyState title="Address check not run" className="py-4" />
        )}
      </Section>

      <Section title="Found by skip trace" icon={Search}>
        {traceQ.isLoading ? (
          <Skeleton variant="line" rows={3} />
        ) : traceQ.error ? (
          <ErrorState error={traceQ.error} onRetry={() => void traceQ.refetch()} />
        ) : addresses.length === 0 ? (
          <EmptyState
            title={traceQ.data?.length ? "No addresses found by skip trace" : "No skip trace has been run"}
            hint="Run a lookup from Verifications → Skip Tracer"
            className="py-4"
          />
        ) : (
          <ul className="space-y-2 text-xs">
            {addresses.map((a, i) => {
              const d = a.address_details;
              const text = [d.address, d.locality, d.city, d.state, d.pincode].filter(Boolean).join(", ");
              const ins = a.address_insights;
              return (
                <li key={i} className="rounded border border-line p-2">
                  <div className="font-medium text-ink">{text || "—"}</div>
                  <div className="text-muted">
                    {[
                      "Digitap",
                      ins.address_quality?.level ? `confidence ${ins.address_quality.level}` : null,
                      ins.reported_date ? `found ${formatDate(ins.reported_date)}` : null,
                    ].filter(Boolean).join(" · ")}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Section>
    </div>
  );
}
