"use client";

import * as React from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { EmptyState, Skeleton, toast } from "@/components/ui";
import { DocCard } from "@/components/staff/doc-card";
import {
  DocumentUpload,
  Section,
  docTypeLabel,
  useCustomerDocumentGroups,
  viewDocument,
} from "@/components/staff/detail-parts";
import { useStaffSession } from "@/lib/auth/staff-session";
import { formatDateTime } from "@/lib/utils";
import { statusLabel, type DocumentView } from "@/lib/api/applications";
import { can as rbacCan, type Permission } from "@/lib/auth/rbac";

const GRID = "grid gap-3 md:grid-cols-3";

/** Customer documents (by type) then Application #N collapsibles, each a 3-column grid of DocCards. */
export function DocumentsTab({ customerId }: { customerId: number }) {
  const { groupsQ, groups, customerDocuments, customerByType, applicationGroups, del, isAdmin } =
    useCustomerDocumentGroups(customerId);
  const sess = useStaffSession().session;
  const role = sess?.role;
  const can = (p: Permission) => rbacCan(sess?.realRole, role, p);
  const canUpload = role != null && can("document:upload");
  const [busy, setBusy] = React.useState<string | null>(null);
  const [openIds, setOpenIds] = React.useState<Set<number> | null>(null);
  // The newest application starts open, once data arrives.
  const open = openIds ?? new Set(groups.length ? [groups[0].applicationId] : []);
  const toggle = (id: number) => {
    const next = new Set(open);
    if (!next.delete(id)) next.add(id);
    setOpenIds(next);
  };

  if (groupsQ.isLoading) return <Skeleton variant="line" rows={3} />;
  if (groups.length === 0) return <EmptyState title="No documents uploaded." />;

  // Stable numbers: count every document, collapsed groups included.
  const numbers = new Map<string, number>();
  Array.from(customerByType.values()).flat().forEach(({ applicationId, doc }) => numbers.set(`${applicationId}-${doc.id}`, numbers.size + 1));
  applicationGroups.forEach((g) => g.documents.forEach((doc) => numbers.set(`${g.applicationId}-${doc.id}`, numbers.size + 1)));
  const num = (appId: number, doc: DocumentView) => numbers.get(`${appId}-${doc.id}`) ?? 0;
  const openDoc = async (appId: number, doc: DocumentView) => {
    const key = `${appId}-${doc.id}`;
    if (busy === key) return;
    setBusy(key);
    try {
      await viewDocument(appId, doc);
    } catch {
      toast.error("Could not open the document");
    } finally {
      setBusy(null);
    }
  };
  const card = (appId: number, doc: DocumentView, label?: string) => (
    <DocCard
      key={`${appId}-${doc.id}`}
      n={num(appId, doc)}
      type={label ?? docTypeLabel(doc.docType)}
      date={formatDateTime(doc.uploadedAt)}
      meta={`Application #${appId}`}
      password={doc.filePassword}
      onView={() => void openDoc(appId, doc)}
      busy={busy === `${appId}-${doc.id}`}
      onDelete={isAdmin ? () => del.mutate({ appId, docId: doc.id }) : undefined}
      deleting={del.isPending && del.variables?.appId === appId && del.variables.docId === doc.id}
    />
  );

  return (
    <div className="space-y-3">
      {customerDocuments.length > 0 && (
        <Section title="Customer documents">
          <div className="space-y-4">
            {Array.from(customerByType.entries()).map(([docType, entries]) => (
              <div key={docType}>
                <h4 className="mb-1.5 text-xs font-semibold text-navy">{docTypeLabel(docType)}</h4>
                <div className={GRID}>{entries.map(({ applicationId, doc }) => card(applicationId, doc))}</div>
              </div>
            ))}
          </div>
        </Section>
      )}

      <div className="text-xs font-semibold uppercase tracking-wide text-muted">Application documents</div>
      {applicationGroups.map((g) => (
        <div key={g.applicationId} className="rounded border border-line">
          <button
            type="button"
            onClick={() => toggle(g.applicationId)}
            aria-expanded={open.has(g.applicationId)}
            className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left"
          >
            <span className="flex items-center gap-1.5 text-sm font-semibold text-ink">
              {open.has(g.applicationId) ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              Application #{g.applicationId}
              <span className="font-normal text-muted"> · {statusLabel(g.applicationStatus)}</span>
            </span>
            <span className="rounded-full bg-navy-tint px-2 py-0.5 text-[8.8px] font-semibold text-navy">
              {g.documents.length}
            </span>
          </button>
          {open.has(g.applicationId) && (
            <div className="border-t border-line p-3">
              {g.documents.length === 0 ? (
                <EmptyState title="No documents uploaded." className="py-4" />
              ) : (
                <div className={GRID}>{g.documents.map((doc) => card(g.applicationId, doc))}</div>
              )}
              {canUpload && (
                <DocumentUpload
                  applicationId={g.applicationId}
                  customerId={customerId}
                  existingCategories={g.documents.map((d) => d.docType)}
                  canDelete={isAdmin}
                />
              )}
            </div>
          )}
        </div>
      ))}

      {del.error && <p className="text-xs text-error-700">Could not delete the document.</p>}
      {!canUpload && <p className="text-xs text-muted">Your role cannot upload or replace documents.</p>}
    </div>
  );
}
