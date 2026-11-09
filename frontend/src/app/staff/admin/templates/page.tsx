"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Plus, RefreshCw } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { EmptyState, ErrorState, Select, Skeleton } from "@/components/ui";
import { Tabs } from "@/components/ui/tabs";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice, PermissionGate } from "@/components/staff/live-pipeline";
import { useQueueSelection } from "@/components/staff/pipeline/bulk-actions";
import { SendMessageDialog, type MessageTargetItem } from "@/components/staff/send-message-dialog";
import {
  customersApi,
  featureFlagsApi,
  storedTemplatesApi,
  type MessageChannel,
  type StoredTemplate,
  type StoredTemplateInput,
} from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

const CHANNELS: { key: MessageChannel; label: string }[] = [
  { key: "SMS", label: "SMS" },
  { key: "WHATSAPP", label: "WhatsApp" },
  { key: "EMAIL", label: "Email" },
];

const VARIABLE_OPTIONS = [
  { value: "name", label: "Customer name" },
  { value: "amount", label: "Amount outstanding" },
  { value: "loanId", label: "Loan number" },
  { value: "daysOverdue", label: "Days overdue" },
  { value: "dailyCharge", label: "Daily late charge" },
  { value: "dueDate", label: "Due date" },
  { value: "dueDatePlus5", label: "Due date + 5 days" },
  { value: "salaryDate", label: "Next salary date" },
  { value: "in:text", label: "Typed when sending (text)" },
  { value: "in:date", label: "Typed when sending (date)" },
];

const STATUS_TONE: Record<StoredTemplate["status"], string> = {
  DRAFT: "text-muted",
  SUBMITTED: "text-warning-700",
  ACTIVE: "text-success-700",
  REJECTED: "text-error-700",
};

function statusLabel(channel: MessageChannel, status: StoredTemplate["status"]): string {
  if (channel === "WHATSAPP") {
    return { DRAFT: "Draft", SUBMITTED: "Pending", ACTIVE: "Approved", REJECTED: "Rejected" }[status];
  }
  return { DRAFT: "Draft", SUBMITTED: "Submitted", ACTIVE: "Active", REJECTED: "Rejected" }[status];
}

const PLACEHOLDER_HINT: Record<MessageChannel, string> = {
  SMS: "Paste the wording exactly as registered on DLT, with {#var#} for each variable.",
  WHATSAPP: "Use {{1}}, {{2}} for variables. No double quotes, no variable at the very start or end.",
  EMAIL: "Use {{1}}, {{2}} for variables, in the subject or the body.",
};

/** How many variables the wording needs: one per {#var#} on SMS, one per distinct {{n}} elsewhere. */
function placeholderCount(channel: MessageChannel, text: string): number {
  if (channel === "SMS") return text.split("{#var#}").length - 1;
  return new Set(text.match(/\{\{\d+\}\}/g) ?? []).size;
}

type Variable = { kind: string; label: string };

function toVariable(v: string): Variable {
  if (!v.startsWith("in:")) return { kind: v, label: "" };
  const [a, b, ...rest] = v.split(":");
  return { kind: `${a}:${b}`, label: rest.join(":") };
}

function fromVariable(v: Variable): string {
  return v.kind.startsWith("in:") ? `${v.kind}:${v.label.trim()}` : v.kind;
}

/** Admin · the SMS, WhatsApp and email template library, with status, add and send. */
export default function AdminTemplatesPage() {
  return (
    <PermissionGate permission="message:send" fallback={<NoAccessNotice message="Admin access only." />}>
      <Templates />
    </PermissionGate>
  );
}

function Templates() {
  const [tab, setTab] = React.useState<MessageChannel>("SMS");
  const flags = useQuery({ queryKey: ["feature-flags"], queryFn: () => featureFlagsApi.get(), staleTime: 60_000 });
  const off = flags.data?.["admin-messaging"] !== true;

  return (
    <div>
      <PageHeader title="Templates" subtitle="SMS, WhatsApp and email templates, with their status. Admin only." />
      {off && (
        <p role="status" className="mb-3 rounded border border-warning-200 bg-warning-50 p-2 text-sm text-ink">
          Customer messaging is switched off (admin-messaging flag). Templates can be read, but not added, changed or sent.
        </p>
      )}
      <Tabs tabs={CHANNELS} active={tab} onChange={(k) => setTab(k as MessageChannel)} className="mb-4" />
      <ChannelPanel key={tab} channel={tab} off={off} />
    </div>
  );
}

function ChannelPanel({ channel, off }: { channel: MessageChannel; off: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["stored-templates", channel],
    queryFn: () => storedTemplatesApi.list(channel),
  });
  const [editing, setEditing] = React.useState<StoredTemplate | "new" | null>(null);
  const [sending, setSending] = React.useState<StoredTemplate | null>(null);
  const [targets, setTargets] = React.useState<MessageTargetItem[] | null>(null);
  const [notice, setNotice] = React.useState<string | null>(null);

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["stored-templates", channel] });
    void qc.invalidateQueries({ queryKey: ["customer-message-templates"] });
  };

  const refresh = useMutation({
    mutationFn: () => storedTemplatesApi.refreshWhatsApp(),
    onSuccess: (r) => {
      invalidate();
      setNotice(
        `${r.updated} updated.` + (r.notFound.length ? ` Not on SmartChat yet: ${r.notFound.join(", ")}.` : ""),
      );
    },
    onError: (e) => setNotice(formatApiError(e, "Could not read templates from SmartChat.")),
  });

  const rows = q.data ?? [];
  const closeSend = () => {
    setSending(null);
    setTargets(null);
  };

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <button type="button" disabled={off} onClick={() => setEditing("new")} className="btn btn-sm btn-navy disabled:opacity-50">
          <Plus size={14} /> Add template
        </button>
        {channel === "WHATSAPP" && (
          <>
            <button
              type="button"
              disabled={off || refresh.isPending}
              onClick={() => refresh.mutate()}
              className="btn btn-sm btn-outline disabled:opacity-50"
            >
              {refresh.isPending ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />} Refresh status from SmartChat
            </button>
            <p className="text-xs text-muted">
              SmartChat cannot create templates through its API. File the template in the SmartChat portal, add it here under the
              same name, then refresh to pick up its status.
            </p>
          </>
        )}
        {channel === "SMS" && <p className="text-xs text-muted">The DLT portal has no API, so status and DLT ids are kept here by hand.</p>}
      </div>
      {notice && (
        <p role="status" className="mb-3 text-xs text-ink">
          {notice}
        </p>
      )}

      {q.isLoading ? (
        <Skeleton variant="table" rows={6} cols={6} className="rounded border border-line bg-white" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
          <div className="staff-table-scroll">
            <table className="staff-data-table">
              <caption className="sr-only">{channel} templates</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Wording</th>
                  <th scope="col">Category</th>
                  <th scope="col">Status</th>
                  <th scope="col">{channel === "SMS" ? "DLT id" : channel === "WHATSAPP" ? "Stored name" : "Provider id"}</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {rows.map((t) => (
                  <tr key={t.id} className="hover:bg-grey-50">
                    <td className="font-semibold text-ink">{t.name}</td>
                    <td className="max-w-[28rem] whitespace-pre-wrap text-xs text-muted">
                      {t.subject && <span className="block font-semibold text-ink">{t.subject}</span>}
                      {t.body || <span className="text-warning-700">Wording not filled in</span>}
                    </td>
                    <td className="whitespace-nowrap">{t.category === "MARKETING" ? "Marketing" : "Service"}</td>
                    <td className="whitespace-nowrap">
                      <span className={`font-semibold ${STATUS_TONE[t.status]}`}>{statusLabel(channel, t.status)}</span>
                      {t.rejectionReason && <span className="block max-w-[14rem] whitespace-normal text-xs text-muted">{t.rejectionReason}</span>}
                    </td>
                    <td className="whitespace-nowrap font-mono text-xs text-muted">{t.providerId ?? "—"}</td>
                    <td className="whitespace-nowrap">
                      <div className="flex gap-2">
                        <button
                          type="button"
                          disabled={off}
                          onClick={() => setEditing(t)}
                          className="btn btn-sm btn-outline disabled:opacity-50"
                        >
                          Edit
                        </button>
                        <button
                          type="button"
                          disabled={off || t.unsendableReason != null}
                          title={t.unsendableReason ?? undefined}
                          onClick={() => setSending(t)}
                          className="btn btn-sm btn-navy disabled:opacity-50"
                        >
                          Send
                        </button>
                      </div>
                      {t.unsendableReason && <span className="mt-1 block whitespace-normal text-xs text-muted">{t.unsendableReason}</span>}
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && <EmptyState title="No templates yet." inTable={6} />}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {editing && (
        <TemplateForm
          channel={channel}
          template={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            invalidate();
            setEditing(null);
          }}
        />
      )}
      {sending && !targets && (
        <RecipientPicker
          single={sending.variables.some((v) => v.startsWith("in:"))}
          templateName={sending.name}
          onCancel={closeSend}
          onPick={setTargets}
        />
      )}
      {sending && targets && <SendMessageDialog open onClose={closeSend} targets={targets} initialTemplateKey={sending.key} />}
    </div>
  );
}

function RecipientPicker({
  single,
  templateName,
  onCancel,
  onPick,
}: {
  single: boolean;
  templateName: string;
  onCancel: () => void;
  onPick: (targets: MessageTargetItem[]) => void;
}) {
  const [search, setSearch] = React.useState("");
  const q = useQuery({
    queryKey: ["template-recipients", search],
    queryFn: () => customersApi.page({ q: search, page: 1, size: 25 }),
  });
  const rows = React.useMemo(() => q.data?.rows ?? [], [q.data]);
  const sel = useQueueSelection(React.useMemo(() => rows.map((c) => c.customerId), [rows]));
  const picked = rows.filter((c) => sel.selected.has(c.customerId));

  return (
    <Dialog open onClose={onCancel} aria-label="Choose customers" size="lg">
      <DialogHeader>
        <DialogTitle>Send {templateName} to</DialogTitle>
      </DialogHeader>
      <div className="space-y-2">
        <div className="field">
          <label htmlFor="tpl-search">Search customers</label>
          <input id="tpl-search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Name, mobile, PAN or customer ID" />
        </div>
        {single && <p className="text-xs text-muted">This template has values typed per customer, so pick one customer.</p>}
        {q.isError ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} className="py-4" />
        ) : q.isLoading ? (
          <p className="text-sm text-muted">Loading customers…</p>
        ) : rows.length === 0 ? (
          <p className="text-sm text-muted">No customers match.</p>
        ) : (
          <ul className="max-h-72 space-y-1 overflow-y-auto text-sm">
            {rows.map((c) => (
              <li key={c.customerId}>
                <label className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={sel.selected.has(c.customerId)}
                    disabled={single && sel.selected.size > 0 && !sel.selected.has(c.customerId)}
                    onChange={() => sel.toggle(c.customerId)}
                  />
                  {c.name ?? "Customer"} <span className="text-xs text-muted">#{c.customerId}</span>
                </label>
              </li>
            ))}
          </ul>
        )}
      </div>
      <DialogFooter>
        <button type="button" onClick={onCancel} className="btn btn-sm btn-outline">
          Cancel
        </button>
        <button
          type="button"
          disabled={picked.length === 0}
          onClick={() => onPick(picked.map((c) => ({ customerId: c.customerId, label: `${c.name ?? "Customer"} (#${c.customerId})` })))}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          Continue with {picked.length}
        </button>
      </DialogFooter>
    </Dialog>
  );
}

function TemplateForm({
  channel,
  template,
  onClose,
  onSaved,
}: {
  channel: MessageChannel;
  template: StoredTemplate | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = React.useState(template?.name ?? "");
  const [subject, setSubject] = React.useState(template?.subject ?? "");
  const [body, setBody] = React.useState(template?.body ?? "");
  const [category, setCategory] = React.useState(template?.category ?? "SERVICE");
  const [rule, setRule] = React.useState(template?.rule ?? "LIVE");
  const [status, setStatus] = React.useState<StoredTemplate["status"]>(template?.status ?? "DRAFT");
  const [providerId, setProviderId] = React.useState(template?.providerId ?? "");
  const [remarks, setRemarks] = React.useState(template?.rejectionReason ?? "");
  const [variables, setVariables] = React.useState<Variable[]>((template?.variables ?? []).map(toVariable));

  const count = placeholderCount(channel, `${channel === "EMAIL" ? subject : ""}\n${body}`);
  // One picker per placeholder; a new one defaults to the customer's name.
  const shown = Array.from({ length: count }, (_, i) => variables[i] ?? { kind: "name", label: "" });
  const setVariable = (i: number, patch: Partial<Variable>) =>
    setVariables(shown.map((v, j) => (j === i ? { ...v, ...patch } : v)));
  const typedOk = shown.every((v) => !v.kind.startsWith("in:") || v.label.trim() !== "");

  const save = useMutation({
    mutationFn: () => {
      const input: StoredTemplateInput = {
        channel,
        name,
        subject: channel === "EMAIL" ? subject : null,
        body,
        category,
        rule,
        variables: shown.map(fromVariable),
        ...(channel === "SMS" ? { status, providerId, remarks } : {}),
      };
      return template ? storedTemplatesApi.update(template.id, input) : storedTemplatesApi.create(input);
    },
    onSuccess: onSaved,
  });

  return (
    <Dialog open onClose={onClose} aria-label={template ? "Edit template" : "Add template"} size="lg">
      <DialogHeader>
        <DialogTitle>{template ? `Edit ${template.name}` : `Add ${channel === "WHATSAPP" ? "WhatsApp" : channel === "EMAIL" ? "email" : "SMS"} template`}</DialogTitle>
      </DialogHeader>
      <div className="max-h-[65vh] space-y-3 overflow-y-auto">
        <div className="field">
          <label htmlFor="tpl-name">Name</label>
          <input id="tpl-name" value={name} disabled={template != null} onChange={(e) => setName(e.target.value)} />
        </div>
        {channel === "EMAIL" && (
          <div className="field">
            <label htmlFor="tpl-subject">Subject</label>
            <input id="tpl-subject" value={subject} maxLength={200} onChange={(e) => setSubject(e.target.value)} />
          </div>
        )}
        <div className="field">
          <label htmlFor="tpl-body">Wording</label>
          <textarea id="tpl-body" rows={5} value={body} onChange={(e) => setBody(e.target.value)} />
          <p className="mt-1 text-xs text-muted">{PLACEHOLDER_HINT[channel]}</p>
        </div>
        {shown.map((v, i) => (
          <div key={i} className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <Select
              label={`Variable ${i + 1}`}
              value={v.kind}
              onChange={(e) => setVariable(i, { kind: e.target.value })}
              options={VARIABLE_OPTIONS}
            />
            {v.kind.startsWith("in:") && (
              <div className="field">
                <label htmlFor={`tpl-var-${i}`}>Label shown when sending</label>
                <input id={`tpl-var-${i}`} value={v.label} maxLength={40} onChange={(e) => setVariable(i, { label: e.target.value.replace(/:/g, "") })} />
              </div>
            )}
          </div>
        ))}
        <Select
          label="Category"
          value={category}
          onChange={(e) => setCategory(e.target.value as StoredTemplate["category"])}
          options={[
            { value: "SERVICE", label: "Service" },
            { value: "MARKETING", label: "Marketing" },
          ]}
        />
        <Select
          label="Who can receive it"
          value={rule}
          onChange={(e) => setRule(e.target.value as StoredTemplate["rule"])}
          options={[
            { value: "ANY", label: "Any customer" },
            { value: "LIVE", label: "Customers with a live loan" },
            { value: "OVERDUE", label: "Customers with an overdue loan" },
          ]}
        />
        {channel === "SMS" && (
          <>
            <Select
              label="Status"
              value={status}
              onChange={(e) => setStatus(e.target.value as StoredTemplate["status"])}
              options={[
                { value: "DRAFT", label: "Draft" },
                { value: "SUBMITTED", label: "Submitted to DLT" },
                { value: "ACTIVE", label: "Active" },
                { value: "REJECTED", label: "Rejected" },
              ]}
            />
            <div className="field">
              <label htmlFor="tpl-dlt">DLT template id</label>
              <input id="tpl-dlt" inputMode="numeric" value={providerId} onChange={(e) => setProviderId(e.target.value.replace(/\D/g, ""))} />
            </div>
            <div className="field">
              <label htmlFor="tpl-remarks">Remarks</label>
              <input id="tpl-remarks" value={remarks} maxLength={1000} onChange={(e) => setRemarks(e.target.value)} />
            </div>
          </>
        )}
        {save.error != null && <p className="text-xs text-error-700">{formatApiError(save.error, "Could not save.")}</p>}
      </div>
      <DialogFooter>
        <button type="button" onClick={onClose} disabled={save.isPending} className="btn btn-sm btn-outline">
          Cancel
        </button>
        <button
          type="button"
          disabled={save.isPending || name.trim() === "" || !typedOk}
          onClick={() => save.mutate()}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {save.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Save
        </button>
      </DialogFooter>
    </Dialog>
  );
}
