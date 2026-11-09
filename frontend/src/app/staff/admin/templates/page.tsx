"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, Loader2, Plus, RefreshCw, Search } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { EmptyState, ErrorState, Select, Skeleton } from "@/components/ui";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { Tabs } from "@/components/ui/tabs";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice, PermissionGate } from "@/components/staff/live-pipeline";
import { useQueueSelection } from "@/components/staff/pipeline/bulk-actions";
import { SendMessageDialog, type MessageTargetItem } from "@/components/staff/send-message-dialog";
import {
  customerMessagesApi,
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

const STATUS_BADGE: Record<StoredTemplate["status"], BadgeVariant> = {
  DRAFT: "neutral",
  SUBMITTED: "warning",
  ACTIVE: "success",
  REJECTED: "error",
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
      <PageHeader title="Templates" subtitle="Every SMS, WhatsApp and email template, its approval status, and who can receive it." />
      {off && (
        <p role="status" className="mb-4 rounded-lg border border-warning-100 bg-warning-50 px-3 py-2 text-sm text-ink">
          Customer messaging is switched off (admin-messaging flag). Templates can be read, but not added, changed or sent.
        </p>
      )}
      <Tabs tabs={CHANNELS} active={tab} onChange={(k) => setTab(k as MessageChannel)} className="mb-4" />
      <ChannelPanel key={tab} channel={tab} off={off} />
    </div>
  );
}

type StatusFilter = "ALL" | StoredTemplate["status"];

const STATUS_ORDER: Record<StoredTemplate["status"], number> = { ACTIVE: 0, SUBMITTED: 1, DRAFT: 2, REJECTED: 3 };

const TILES: { key: StatusFilter; label: string; idle: string; on: string }[] = [
  { key: "ALL", label: "All templates", idle: "border-line bg-white text-navy", on: "border-navy bg-navy text-white" },
  { key: "ACTIVE", label: "Ready to send", idle: "border-success-100 bg-success-50 text-success-800", on: "border-success-700 bg-success-700 text-white" },
  { key: "SUBMITTED", label: "In review", idle: "border-warning-100 bg-warning-50 text-warning-800", on: "border-warning-700 bg-warning-700 text-white" },
  { key: "REJECTED", label: "Rejected", idle: "border-error-100 bg-error-50 text-error-800", on: "border-error-700 bg-error-700 text-white" },
];

const OPTION_LABEL: Record<string, string> = Object.fromEntries(VARIABLE_OPTIONS.map((o) => [o.value, o.label]));

function variableLabel(variable: string | undefined, position: number): string {
  if (!variable) return `Variable ${position}`;
  if (variable.startsWith("in:")) return toVariable(variable).label || `Variable ${position}`;
  return OPTION_LABEL[variable] ?? variable;
}

/** The message as the customer reads it, with each variable shown by what fills it. */
function Wording({ template }: { template: StoredTemplate }) {
  if (!template.body) {
    return (
      <p className="rounded-md bg-grey-50 px-3 py-2 text-xs italic text-muted">
        {template.channel === "WHATSAPP" ? "Wording is kept on SmartChat. Use Edit to save a copy here." : "Wording not filled in."}
      </p>
    );
  }
  let next = 0;
  return (
    <p className="whitespace-pre-wrap break-words rounded-md bg-ivory px-3 py-2 text-[13px] leading-relaxed text-ink">
      {template.body.split(/(\{#var#\}|\{\{\d+\}\})/).map((part, i) => {
        const numbered = part.match(/^\{\{(\d+)\}\}$/);
        if (part !== "{#var#}" && !numbered) return <React.Fragment key={i}>{part}</React.Fragment>;
        const position = numbered ? Number(numbered[1]) : ++next;
        return (
          <span key={i} className="mx-0.5 inline-block rounded bg-navy-tint px-1.5 text-xs font-semibold text-navy">
            {variableLabel(template.variables[position - 1], position)}
          </span>
        );
      })}
    </p>
  );
}

function CopyId({ value }: { value: string }) {
  const [copied, setCopied] = React.useState(false);
  return (
    <button
      type="button"
      aria-label={`Copy ${value}`}
      title="Copy"
      className="inline-flex items-center gap-1 break-all text-left font-mono text-xs text-muted hover:text-navy"
      onClick={() => {
        void navigator.clipboard?.writeText(value);
        setCopied(true);
        window.setTimeout(() => setCopied(false), 1500);
      }}
    >
      {value} {copied ? <Check size={12} className="flex-shrink-0 text-success-700" /> : <Copy size={12} className="flex-shrink-0" />}
    </button>
  );
}

function TemplateCard({
  template: t,
  off,
  sendKey,
  onEdit,
  onSend,
}: {
  template: StoredTemplate;
  off: boolean;
  sendKey: string | null;
  onEdit: () => void;
  onSend: () => void;
}) {
  const idLabel = t.channel === "SMS" ? "DLT id" : t.channel === "WHATSAPP" ? "Stored as" : "Provider id";
  // A listed template that the catalogue can send is not really unsendable, whatever the row says.
  const note = sendKey ? null : t.unsendableReason;
  return (
    <article className="flex flex-col gap-3 rounded-lg border border-line bg-white p-4 shadow-sm transition-shadow hover:shadow-md">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <h3 className="break-all font-mono text-sm font-semibold text-navy">{t.name}</h3>
          <div className="mt-1.5 flex flex-wrap gap-1.5">
            <Badge size="sm" variant={STATUS_BADGE[t.status]}>{statusLabel(t.channel, t.status)}</Badge>
            <Badge size="sm" variant="neutral">{t.category === "MARKETING" ? "Marketing" : "Service"}</Badge>
          </div>
        </div>
        <div className="flex flex-shrink-0 gap-2">
          <button type="button" disabled={off} onClick={onEdit} className="btn btn-sm btn-outline disabled:opacity-50">
            Edit
          </button>
          <button
            type="button"
            disabled={off || sendKey == null}
            title={note ?? undefined}
            onClick={onSend}
            className="btn btn-sm btn-navy disabled:opacity-50"
          >
            Send
          </button>
        </div>
      </div>

      {t.subject && <p className="text-sm font-semibold text-ink">{t.subject}</p>}
      <Wording template={t} />

      {t.rejectionReason && (
        <p className="rounded-md border border-error-100 bg-error-50 px-3 py-2 text-xs text-error-800">{t.rejectionReason}</p>
      )}
      {(t.providerId || note) && (
        <div className="mt-auto flex flex-wrap items-center justify-between gap-x-4 gap-y-1 border-t border-line pt-2">
          {t.providerId ? (
            <span className="flex items-center gap-1.5 text-xs text-muted">
              {idLabel} <CopyId value={t.providerId} />
            </span>
          ) : (
            <span />
          )}
          {note && <span className="text-xs text-muted">{note}</span>}
        </div>
      )}
    </article>
  );
}

function ChannelPanel({ channel, off }: { channel: MessageChannel; off: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["stored-templates", channel],
    queryFn: () => storedTemplatesApi.list(channel),
  });
  const catalogue = useQuery({
    queryKey: ["customer-message-templates"],
    queryFn: () => customerMessagesApi.templates(),
    enabled: channel === "WHATSAPP" && !off,
  });
  const [editing, setEditing] = React.useState<StoredTemplate | "new" | null>(null);
  const [sending, setSending] = React.useState<{ template: StoredTemplate; key: string } | null>(null);
  const [targets, setTargets] = React.useState<MessageTargetItem[] | null>(null);
  const [notice, setNotice] = React.useState<string | null>(null);
  const [filter, setFilter] = React.useState<StatusFilter>("ALL");
  const [search, setSearch] = React.useState("");

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
    onError: (e) =>
      setNotice(`${formatApiError(e, "Could not read templates from SmartChat.")} You can still update a status with Edit.`),
  });

  const all = React.useMemo(
    () => [...(q.data ?? [])].sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status]),
    [q.data],
  );
  const counts = (key: StatusFilter) => (key === "ALL" ? all.length : all.filter((t) => t.status === key).length);
  const term = search.trim().toLowerCase();
  const matching = all.filter(
    (t) =>
      (filter === "ALL" || t.status === filter) &&
      (term === "" || t.name.toLowerCase().includes(term) || (t.body ?? "").toLowerCase().includes(term)),
  );
  // Rejected templates fold away so they don't bury the ones that can be used.
  const foldRejected = filter === "ALL";
  const main = foldRejected ? matching.filter((t) => t.status !== "REJECTED") : matching;
  const rejected = foldRejected ? matching.filter((t) => t.status === "REJECTED") : [];

  /** What Send opens: the stored template, or for a listed WhatsApp one its entry in the send catalogue. */
  const sendKeyFor = (t: StoredTemplate): string | null => {
    if (!t.systemManaged) return t.unsendableReason == null ? t.key : null;
    if (t.channel !== "WHATSAPP" || t.status !== "ACTIVE") return null;
    const key = t.name.replace(/^db_/, "").replace(/_v1_164$/, "");
    return catalogue.data?.some((c) => c.key === key) ? key : null;
  };
  const singleOnly = (s: { template: StoredTemplate; key: string }) =>
    catalogue.data?.find((c) => c.key === s.key)?.singleOnly ?? s.template.variables.some((v) => v.startsWith("in:"));

  const closeSend = () => {
    setSending(null);
    setTargets(null);
  };
  const card = (t: StoredTemplate) => (
    <TemplateCard
      key={t.id}
      template={t}
      off={off}
      sendKey={sendKeyFor(t)}
      onEdit={() => setEditing(t)}
      onSend={() => {
        const key = sendKeyFor(t);
        if (key) setSending({ template: t, key });
      }}
    />
  );

  return (
    <div>
      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        {TILES.map((tile) => (
          <button
            key={tile.key}
            type="button"
            aria-pressed={filter === tile.key}
            onClick={() => setFilter(tile.key)}
            className={`rounded-lg border px-4 py-3 text-left transition-colors ${filter === tile.key ? tile.on : tile.idle}`}
          >
            <span className="block text-2xl font-bold leading-none">{counts(tile.key)}</span>
            <span className="mt-1 block text-xs font-semibold">{tile.label}</span>
          </button>
        ))}
      </div>

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <div className="relative min-w-[14rem] flex-1">
          <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="search"
            aria-label="Search templates"
            placeholder="Search by name or wording"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full rounded-lg border border-line bg-white py-2 pl-9 pr-3 text-sm text-ink focus:border-navy focus:outline-none"
          />
        </div>
        {channel === "WHATSAPP" && (
          <button
            type="button"
            disabled={off || refresh.isPending}
            onClick={() => refresh.mutate()}
            className="btn btn-sm btn-outline disabled:opacity-50"
          >
            {refresh.isPending ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />} Refresh status from SmartChat
          </button>
        )}
        <button type="button" disabled={off} onClick={() => setEditing("new")} className="btn btn-sm btn-navy disabled:opacity-50">
          <Plus size={14} /> Add template
        </button>
      </div>
      <p className="mb-4 text-xs text-muted">
        {channel === "WHATSAPP"
          ? "New templates are filed on the SmartChat portal first, then added here under the same name."
          : channel === "SMS"
            ? "DLT has no API, so status and DLT ids are kept here by hand. A new SMS also needs adding in Ultron before it can send."
            : "Email templates can be sent as soon as they are saved."}
      </p>
      {notice && (
        <p role="status" className="mb-4 rounded-lg border border-line bg-white px-3 py-2 text-xs text-ink">
          {notice}
        </p>
      )}

      {q.isLoading ? (
        <Skeleton variant="table" rows={6} cols={2} className="rounded-lg border border-line bg-white" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : matching.length === 0 ? (
        <EmptyState title={all.length === 0 ? "No templates yet." : "No templates match."} />
      ) : (
        <>
          <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">{main.map(card)}</div>
          {rejected.length > 0 && (
            <details className="mt-4 rounded-lg border border-line bg-white p-3">
              <summary className="cursor-pointer text-sm font-semibold text-navy">Rejected ({rejected.length})</summary>
              <div className="mt-3 grid grid-cols-1 gap-3 xl:grid-cols-2">{rejected.map(card)}</div>
            </details>
          )}
        </>
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
          single={singleOnly(sending)}
          templateName={sending.template.name}
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

  // A template listed from SmartChat: its wording lives with Meta, so only the status is kept here.
  const listedOnly = channel === "WHATSAPP" && template?.systemManaged === true;
  const count = listedOnly ? 0 : placeholderCount(channel, `${channel === "EMAIL" ? subject : ""}\n${body}`);
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
        ...(channel !== "EMAIL" ? { status, providerId, remarks } : {}),
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
          <textarea id="tpl-body" rows={5} value={body} readOnly={listedOnly && Boolean(template?.body)} onChange={(e) => setBody(e.target.value)} />
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
        {!listedOnly && <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
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
        </div>}
        {channel !== "EMAIL" && (
          <>
            <Select
              label="Status"
              value={status}
              onChange={(e) => setStatus(e.target.value as StoredTemplate["status"])}
              options={[
                { value: "DRAFT", label: "Draft" },
                { value: "SUBMITTED", label: channel === "SMS" ? "Submitted to DLT" : "Pending at SmartChat" },
                { value: "ACTIVE", label: channel === "SMS" ? "Active" : "Approved" },
                { value: "REJECTED", label: "Rejected" },
              ]}
            />
            <div className="field">
              <label htmlFor="tpl-dlt">{channel === "SMS" ? "DLT template id" : "Name stored on SmartChat"}</label>
              <input
                id="tpl-dlt"
                inputMode={channel === "SMS" ? "numeric" : undefined}
                value={providerId}
                onChange={(e) => setProviderId(channel === "SMS" ? e.target.value.replace(/\D/g, "") : e.target.value.trim())}
              />
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
