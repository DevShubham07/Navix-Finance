"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, Check, Pencil, Plus, Users, MessageCircle } from "lucide-react";
import { EmptyState, ErrorState, Skeleton, toast } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { Section } from "@/components/staff/detail-parts";
import { errMessage } from "@/components/staff/pipeline/hooks";
import { hasPermission } from "@/lib/auth/rbac";
import { useStaffSession } from "@/lib/auth/staff-session";
import { staffApi, REFERENCE_RELATIONS, type ReferenceInput } from "@/lib/api/applications";

const RELATION_LABEL: Record<string, string> = {
  PARENT: "Parent", SPOUSE: "Spouse", SIBLING: "Sibling", RELATIVE: "Relative",
  FRIEND: "Friend", COLLEAGUE: "Colleague", MANAGER: "Manager", NEIGHBOUR: "Neighbour",
};

/**
 * The two contacts the borrower named in the offer journey (V46). Shown to every role rather than
 * scoped to collections: credit reads them as part of the file, and collections is simply the role
 * that eventually calls them. Empty before the borrower reaches that screen.
 *
 * ADMIN additionally gets an inline "Edit" affordance — the backend's `offer/references` endpoint
 * already permits ADMIN (not just the owning borrower), so a staffer can correct a mistyped name/
 * mobile/relation without the borrower having to redo the screen.
 */
export function ReferencesTab({ applicationId }: { applicationId: number | null }) {
  if (applicationId == null) return <EmptyState title="No application to show references for." />;
  return <ReferencesFocus applicationId={applicationId} />;
}

function ReferencesFocus({ applicationId }: { applicationId: number }) {
  const role = useStaffSession().session?.role;
  const canEdit = role != null && hasPermission(role, "customer:manage");
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["staff-references", applicationId],
    queryFn: () => staffApi.references(applicationId),
    retry: false,
  });
  const rows = q.data ?? [];
  const [drafts, setDrafts] = React.useState<ReferenceInput[] | null>(null);
  const asInput = (): ReferenceInput[] => rows.map((r) => ({ fullName: r.fullName, mobile: r.mobile, relation: r.relation }));

  const save = useMutation({
    mutationFn: (list: ReferenceInput[]) => staffApi.saveReferences(applicationId, list),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["staff-references", applicationId] });
      setDrafts(null);
      toast.success("References saved");
    },
  });

  if (q.isLoading) return <Skeleton variant="line" rows={3} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;

  const addBtn = canEdit && drafts == null && rows.length < 2 && (
    <div className="flex justify-end">
      <button
        type="button"
        className="btn btn-sm btn-outline"
        onClick={() => setDrafts([
            ...asInput(),
            ...Array.from({ length: 2 - rows.length }, () => ({ fullName: "", mobile: "", relation: "FRIEND" })),
          ])}
      >
        <Plus size={13} /> Add reference
      </button>
    </div>
  );

  if (drafts != null) {
    const patch = (i: number, p: Partial<ReferenceInput>) =>
      setDrafts((prev) => (prev ?? []).map((x, j) => (j === i ? { ...x, ...p } : x)));
    return (
      <Section icon={Users} title="References">
        <div className="space-y-3">
          {drafts.map((d, i) => (
            <div key={i} className="grid gap-2 rounded border border-line p-2 sm:grid-cols-3">
              <label className="field !mb-0">
                <span className="text-xs">Full name</span>
                <input
                  type="text"
                  value={d.fullName}
                  onChange={(e) => patch(i, { fullName: e.target.value })}
                  className="w-full rounded border border-line px-2 py-1.5 text-sm"
                />
              </label>
              <label className="field !mb-0">
                <span className="text-xs">Mobile</span>
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={10}
                  value={d.mobile}
                  onChange={(e) => patch(i, { mobile: e.target.value })}
                  className="w-full rounded border border-line px-2 py-1.5 text-sm"
                />
              </label>
              <label className="field !mb-0">
                <span className="text-xs">Relation</span>
                <select
                  value={d.relation}
                  onChange={(e) => patch(i, { relation: e.target.value })}
                  className="w-full rounded border border-line px-2 py-1.5 text-sm"
                >
                  {REFERENCE_RELATIONS.map((rel) => (
                    <option key={rel} value={rel}>
                      {RELATION_LABEL[rel] ?? rel}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          ))}
          {save.error && <p className="text-xs text-error-700">{errMessage(save.error)}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={() => setDrafts(null)} disabled={save.isPending} className="btn btn-sm btn-outline">
              Cancel
            </button>
            <button
              type="button"
              onClick={() => save.mutate(drafts)}
              disabled={save.isPending}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {save.isPending ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />}
              Save
            </button>
          </div>
        </div>
      </Section>
    );
  }

  if (rows.length === 0)
    return (
      <div className="space-y-3">
        {addBtn}
        <EmptyState title="No references on file yet." />
      </div>
    );

  return (
    <div className="space-y-3">
      {addBtn}
      {save.error && <p className="text-xs text-error-700">{errMessage(save.error)}</p>}
      <div className="grid gap-3 md:grid-cols-3">
        {rows.map((r) => {
          const wa = r.mobile.replace(/\D/g, "").slice(-10);
          return (
            <Section
              key={r.slot}
              icon={Users}
              title={`Reference ${r.slot}`}
              action={
                canEdit ? (
                  <span className="flex gap-1">
                    <button
                      type="button"
                      className="btn btn-sm btn-outline btn-icon"
                      aria-label={`Edit reference ${r.slot}`}
                      onClick={() => setDrafts(asInput())}
                    >
                      <Pencil size={13} />
                    </button>
                  </span>
                ) : undefined
              }
            >
              <FieldGrid cols={2}>
                <Field label="Name" className="col-span-2">{r.fullName}</Field>
                <Field label="Contact" mono>
                  <span className="inline-flex items-center gap-1.5">
                    {r.mobile}
                    {wa.length === 10 && (
                      <a
                        href={`https://wa.me/91${wa}`}
                        target="_blank"
                        rel="noopener noreferrer"
                        aria-label={`WhatsApp ${r.fullName}`}
                        className="text-success-600"
                      >
                        <MessageCircle size={14} />
                      </a>
                    )}
                  </span>
                </Field>
                <Field label="Relation">{RELATION_LABEL[r.relation] ?? r.relation}</Field>
              </FieldGrid>
            </Section>
          );
        })}
      </div>
    </div>
  );
}
