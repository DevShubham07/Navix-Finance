"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { ClipboardCheck, FileSignature, FileText } from "lucide-react";
import { Badge, EmptyState, toast } from "@/components/ui";
import type { BadgeVariant } from "@/components/ui/badge";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { DocCard } from "@/components/staff/doc-card";
import { Section, viewDocument } from "@/components/staff/detail-parts";
import { stageActionFor, stageActionTab } from "@/components/staff/customer-360/stage-actions";
import type { TabCtx } from "@/components/staff/customer-360/types";
import { formatDate, formatDateTime } from "@/lib/utils";
import { customersApi, staffApi, paiseToINR, type DocumentView } from "@/lib/api/applications";

const str = (d: Record<string, unknown> | undefined, k: string): string | null => {
  const v = d?.[k];
  return typeof v === "string" && v.trim() !== "" ? v : null;
};

const newest = (docs: DocumentView[], type: string) =>
  docs.filter((d) => d.docType.toUpperCase() === type).sort((a, b) => b.id - a.id)[0];

const pill = (variant: BadgeVariant, text: string) => (
  <Badge variant={variant} size="sm" className="ml-1 normal-case tracking-normal">
    {text}
  </Badge>
);

const EMPTY_CARD = "grid place-items-center rounded-xl border border-dashed border-line p-4 text-center text-[10.4px] text-muted";

export function SanctionTab({ app, applicationId, detail, customerId }: TabCtx) {
  const [busy, setBusy] = React.useState<string | null>(null);
  const openDoc = async (key: string, appId: number, doc: Parameters<typeof viewDocument>[1]) => {
    if (busy) return;
    setBusy(key);
    try {
      await viewDocument(appId, doc);
    } catch {
      toast.error("Could not open the document");
    } finally {
      setBusy(null);
    }
  };
  const docsQ = useQuery({
    queryKey: ["customer-documents", customerId],
    queryFn: () => customersApi.documents(customerId),
  });
  const eventsQ = useQuery({
    queryKey: ["staff-events", applicationId],
    queryFn: () => staffApi.events(applicationId as number),
    enabled: applicationId != null,
  });
  const verifQ = useQuery({
    queryKey: ["verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId as number),
    enabled: applicationId != null,
  });
  if (applicationId == null || !app) return <EmptyState title="No application to show yet." />;

  const docs = docsQ.data?.find((g) => g.applicationId === applicationId)?.documents ?? [];
  const events = eventsQ.data ?? [];
  const esign = (verifQ.data ?? []).find((s) => s.checkType === "ESIGN");
  const rejected = app.status === "REJECTED";
  const sanctioned = app.sanctionedAmountPaise != null;
  const ceiling = sanctioned ? paiseToINR(app.sanctionedAmountPaise as number) : null;
  const repayDate = app.approvedRepaymentDate ? formatDate(app.approvedRepaymentDate) : null;
  const action = stageActionTab(app.status) === "sanction" ? stageActionFor(app) : null;

  const latest = (pred: (e: (typeof events)[number]) => boolean) =>
    events.filter(pred).sort((a, b) => b.at.localeCompare(a.at))[0];
  const rejectEvent = latest((e) => e.toStatus === "REJECTED");
  const acceptEvent = latest((e) => e.action === "ACCEPT_OFFER" && e.toStatus === "DISBURSEMENT_PENDING");

  const letter = newest(docs, "SANCTION_LETTER");
  const signed = newest(docs, "SIGNED_AGREEMENT");
  const signedAt = str(esign?.derived, "signedAt");
  const method =
    esign?.status === "PASS" ? (esign.provider === "MANUAL" ? "Drawn signature" : "Aadhaar eSign") : signed ? "Drawn signature" : null;
  const esignPill =
    esign?.status === "PASS"
      ? pill("success", "COMPLETED")
      : esign?.status === "PENDING"
        ? pill("warning", "Awaiting signature")
        : pill("neutral", "Not started");
  const letterPill = signed
    ? pill("success", "Signed by borrower")
    : letter
      ? pill("warning", "Awaiting signature")
      : pill("neutral", "Not generated");

  // Only keys the backend really writes to the ESIGN row (ApplicationVerificationService).
  const esignFields: [string, string | null, boolean?][] = [
    ["Provider", esign?.provider ?? null],
    ["Contract id", esign?.providerTxnId ?? null, true],
    ["Last checked", esign?.checkedAt ? formatDateTime(esign.checkedAt) : null],
    ["Signed at", signedAt ? formatDateTime(signedAt) : null],
    ["Signature ref", str(esign?.derived, "signatureRef"), true],
    ["Capture method", method],
    ["Identity match", str(esign?.derived, "matchMode")],
  ];

  return (
    <div className="space-y-3">
      {action && <div className="flex flex-wrap items-end gap-2">{action}</div>}

      <Section
        icon={ClipboardCheck}
        title="Decision"
        tone={rejected ? "error" : sanctioned ? "success" : "warning"}
        pill={rejected ? pill("error", "Rejected") : sanctioned ? pill("success", "Sanctioned") : pill("warning", "Pending")}
      >
        {rejected ? (
          <FieldGrid cols={4}>
            <Field label="Rejected at" tone="error">{rejectEvent ? formatDateTime(rejectEvent.at) : null}</Field>
            <Field label="Rejected by">{rejectEvent?.actorName ?? app.creditDecidedByName}</Field>
            <Field label="Reason" className="col-span-2">{rejectEvent?.notes}</Field>
          </FieldGrid>
        ) : (
          <FieldGrid cols={4}>
            <Field label="Sanctioned amount" keyLabel tone="navy">{ceiling}</Field>
            <Field
              keyLabel label="Approved repayment date"
              caption={app.salaryCreditDay != null ? `on the borrower's salary day (day ${app.salaryCreditDay})` : undefined}
            >
              {repayDate}
            </Field>
            <Field label="Decided by">{app.creditDecidedByName}</Field>
            <Field label="Decided at">{app.sanctionedAt ? formatDateTime(app.sanctionedAt) : null}</Field>
            <Field label="Recommendation">{detail.profile?.recommendation ?? app.recommendation}</Field>
            <Field label="Remarks" className="col-span-3">{app.sanctionRemarks}</Field>
            {app.markedPendingAt && (
              <>
                <Field label="Marked pending at" tone="warning">{formatDateTime(app.markedPendingAt)}</Field>
                <Field label="Pending reason" tone="warning" className="col-span-3">{app.pendingReason}</Field>
              </>
            )}
          </FieldGrid>
        )}
      </Section>

      <Section icon={FileSignature} title="e-Sign" pill={esignPill}>
        {esign == null ? (
          <p className="text-[10.4px] text-muted">The borrower has not started signing.</p>
        ) : (
          <FieldGrid cols={4}>
            {esignFields
              .filter(([, v]) => v != null)
              .map(([label, v, mono]) => (
                <Field key={label} label={label} mono={mono}>{v}</Field>
              ))}
          </FieldGrid>
        )}
      </Section>

      {!rejected && (
        <Section icon={FileText} title="Sanction letter" pill={letterPill}>
          <div className="grid gap-3 md:grid-cols-2">
            {letter ? (
              <DocCard
                n={1}
                type="Key Fact Statement / sanction letter"
                date={formatDateTime(letter.uploadedAt)}
                meta={`Sanctioned ${ceiling ?? "—"} · Repayment ${repayDate ?? "—"}`}
                onView={() => void openDoc("letter", applicationId, letter)}
                busy={busy === "letter"}
              />
            ) : (
              <div className={EMPTY_CARD}>The sanction letter has not been generated yet.</div>
            )}
            {signed ? (
              <DocCard
                n={2}
                type="Borrower-signed copy"
                date={formatDateTime(signedAt ?? signed.uploadedAt)}
                meta={`${method ?? "Signed"}${detail.profile?.fullName ? ` · Signed by ${detail.profile.fullName}` : ""}`}
                onView={() => void openDoc("signed", applicationId, signed)}
                busy={busy === "signed"}
              />
            ) : (
              <div className={EMPTY_CARD}>Borrower has not signed yet — they are at the eSign step.</div>
            )}
          </div>
          {acceptEvent && (
            <div className="mt-3 border-t border-line pt-3">
              <div className="mb-2 text-[9.6px] font-bold uppercase tracking-wide text-muted">Borrower acceptance</div>
              <FieldGrid cols={4}>
                <Field label="Accepted amount" keyLabel tone="navy" caption={ceiling ? `within the ${ceiling} ceiling` : undefined}>
                  {app.amountRequestedPaise != null ? paiseToINR(app.amountRequestedPaise) : null}
                </Field>
                <Field label="Accepted repayment date" keyLabel>{repayDate}</Field>
                <Field label="Accepted at">{formatDateTime(acceptEvent.at)}</Field>
                <Field label="Consent / T&C" tone="success">✓ Yes</Field>
              </FieldGrid>
            </div>
          )}
        </Section>
      )}
    </div>
  );
}
