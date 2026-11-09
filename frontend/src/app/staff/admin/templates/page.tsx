"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Pencil, Plus, RefreshCw, Search, Trash2 } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { EmptyState, ErrorState, Select, Skeleton } from "@/components/ui";
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
  type TemplatePurpose,
} from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

const CHANNELS: { key: MessageChannel; label: string }[] = [
  { key: "WHATSAPP", label: "WhatsApp" },
  { key: "SMS", label: "SMS" },
  { key: "EMAIL", label: "Email" },
];

/** In page order: what staff send most comes first. */
const SECTIONS: { key: TemplatePurpose; title: string; pill: string }[] = [
  { key: "COLLECTIONS", title: "Collections", pill: "bg-[#FCEEE6] text-[#9A4318]" },
  { key: "REPAYMENT", title: "Repayment", pill: "bg-[#E6EFF8] text-[#1F5A92]" },
  { key: "DISCOUNTS", title: "Discounts", pill: "bg-[#FBF1D8] text-[#7A5005]" },
  { key: "LOAN_UPDATES", title: "Loan updates", pill: "bg-[#E3F3EF] text-[#17675A]" },
  { key: "KYC", title: "KYC", pill: "bg-[#EFEAF8] text-[#57408F]" },
  { key: "REFERENCES", title: "References", pill: "bg-[#EEF1F4] text-[#44505E]" },
  { key: "OTHER", title: "Other", pill: "bg-[#EFEDE6] text-[#5A6473]" },
];

/** How much of a loan a group's customers are known to have. */
type GroupKind = "OVERDUE" | "LIVE" | "ANY";
type Group = { key: string; label: string; kind: GroupKind };

const MAIN_GROUPS: Group[] = [
  { key: "DUE_SOON", label: "Due soon", kind: "LIVE" },
  { key: "OVERDUE_1_7", label: "Overdue 1 to 7", kind: "OVERDUE" },
  { key: "OVERDUE_8_30", label: "8 to 30", kind: "OVERDUE" },
  { key: "OVERDUE_31_60", label: "31 to 60", kind: "OVERDUE" },
  { key: "OVERDUE_61_90", label: "61 to 90", kind: "OVERDUE" },
  { key: "OVERDUE_90_PLUS", label: "90+", kind: "OVERDUE" },
];

const MORE_GROUPS: Group[] = [
  { key: "active", label: "Active", kind: "LIVE" },
  { key: "closed", label: "Closed", kind: "ANY" },
  { key: "reloan", label: "Reloan", kind: "ANY" },
  { key: "approved", label: "Approved", kind: "ANY" },
  { key: "disbursementPending", label: "Disbursement pending", kind: "ANY" },
  { key: "incomplete", label: "Incomplete", kind: "ANY" },
  { key: "pending", label: "Pending", kind: "ANY" },
  { key: "review", label: "Review", kind: "ANY" },
  { key: "hold", label: "Hold", kind: "ANY" },
  { key: "rejected", label: "Rejected", kind: "ANY" },
];

/** Whether everyone in the group can receive a template with this rule. */
function fits(rule: string | null | undefined, group: Group): boolean {
  if (rule === "WINDOW_1_5") return group.key === "OVERDUE_1_7";
  if (rule === "OVERDUE") return group.kind === "OVERDUE";
  if (rule === "LIVE") return group.kind !== "ANY";
  return true;
}

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

/** The wording as one plain line: card text is clamped, the full message shows when sending. */
function plainText(t: StoredTemplate): string {
  if (!t.body) return "Wording is kept on the provider.";
  const label = (position: number) => {
    const v = t.variables[position - 1];
    if (!v) return "[…]";
    if (v.startsWith("in:")) return `[${toVariable(v).label || "…"}]`;
    return `[${(VARIABLE_OPTIONS.find((o) => o.value === v)?.label ?? v).toLowerCase()}]`;
  };
  let next = 0;
  return t.body.replace(/\{#var#\}|\{\{(\d+)\}\}/g, (_, n) => label(n ? Number(n) : ++next));
}

/** Admin · every SMS, WhatsApp and email template, by category, ready to send to a group of customers. */
export default function AdminTemplatesPage() {
  return (
    <PermissionGate permission="message:send" fallback={<NoAccessNotice message="Admin access only." />}>
      <Templates />
    </PermissionGate>
  );
}

type Audience = { group: Group; count: number | null; customerIds: number[]; capped: boolean };

function Templates() {
  const [channel, setChannel] = React.useState<MessageChannel>("WHATSAPP");
  const [groupKey, setGroupKey] = React.useState("");
  const [search, setSearch] = React.useState("");
  const [adding, setAdding] = React.useState(false);
  const flags = useQuery({ queryKey: ["feature-flags"], queryFn: () => featureFlagsApi.get(), staleTime: 60_000 });
  const off = flags.data?.["admin-messaging"] !== true;

  const group = [...MAIN_GROUPS, ...MORE_GROUPS].find((g) => g.key === groupKey) ?? null;
  const members = useQuery({
    queryKey: ["message-audience", groupKey],
    queryFn: () => customerMessagesApi.audience(groupKey),
    enabled: group != null && !off,
  });
  const audience: Audience | null = group && {
    group,
    count: members.data?.count ?? null,
    customerIds: members.data?.customerIds ?? [],
    capped: members.data?.capped ?? false,
  };
  const chip = (on: boolean) =>
    `h-9 rounded-full border px-3.5 text-[13px] font-semibold ${on ? "border-navy bg-navy text-white" : "border-line bg-white text-navy hover:border-navy"}`;
  const inMore = MORE_GROUPS.some((g) => g.key === groupKey);

  return (
    <div className="mx-auto max-w-[1240px]">
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <h1 className="mr-auto font-serif text-2xl font-bold text-navy">Templates</h1>
        <div className="relative">
          <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <input
            type="search"
            aria-label="Search templates"
            placeholder="Search templates"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="h-10 w-56 rounded-lg border border-line bg-white pl-9 pr-3 text-sm text-ink focus:border-navy focus:outline-none"
          />
        </div>
        <div role="tablist" aria-label="Channel" className="flex rounded-lg bg-[#F3F0E7] p-1">
          {CHANNELS.map((c) => (
            <button
              key={c.key}
              type="button"
              role="tab"
              aria-selected={channel === c.key}
              onClick={() => setChannel(c.key)}
              className={`h-8 rounded-md px-3.5 text-[13px] font-semibold ${channel === c.key ? "bg-white text-navy shadow-sm" : "text-muted"}`}
            >
              {c.label}
            </button>
          ))}
        </div>
        <button type="button" disabled={off} onClick={() => setAdding(true)} className="btn btn-sm btn-outline disabled:opacity-50">
          <Plus size={14} /> Add template
        </button>
      </div>

      {off && (
        <p role="status" className="mb-4 rounded-lg border border-warning-100 bg-warning-50 px-3 py-2 text-sm text-ink">
          Customer messaging is switched off (admin-messaging flag). Templates can be read, but not added, changed or sent.
        </p>
      )}

      <div className="mb-8 flex flex-wrap items-center gap-2 rounded-lg border border-line bg-white px-4 py-3">
        <span className="mr-1 text-sm font-bold text-navy">Send to</span>
        <button type="button" aria-pressed={group == null} onClick={() => setGroupKey("")} className={chip(group == null)}>
          Choose customers
        </button>
        {MAIN_GROUPS.map((g) => (
          <button key={g.key} type="button" aria-pressed={groupKey === g.key} onClick={() => setGroupKey(g.key)} className={chip(groupKey === g.key)}>
            {g.label}
          </button>
        ))}
        <select
          aria-label="More groups"
          value={inMore ? groupKey : ""}
          onChange={(e) => setGroupKey(e.target.value)}
          className={`${chip(inMore)} ${inMore ? "" : "pr-2"}`}
        >
          <option value="">More groups</option>
          {MORE_GROUPS.map((g) => (
            <option key={g.key} value={g.key}>
              {g.label}
            </option>
          ))}
        </select>
        {group && (
          <span className="ml-auto text-sm text-muted" role="status">
            {members.isError ? "Could not load this group" : audience?.count == null ? "Counting…" : `${audience.count} customers`}
          </span>
        )}
      </div>

      <ChannelPanel key={channel} channel={channel} off={off} audience={audience} search={search} adding={adding} onAdded={() => setAdding(false)} />
    </div>
  );
}

function ChannelPanel({
  channel,
  off,
  audience,
  search,
  adding,
  onAdded,
}: {
  channel: MessageChannel;
  off: boolean;
  audience: Audience | null;
  search: string;
  adding: boolean;
  onAdded: () => void;
}) {
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
  const [editing, setEditing] = React.useState<StoredTemplate | null>(null);
  const [deleting, setDeleting] = React.useState<StoredTemplate | null>(null);
  const [sending, setSending] = React.useState<{ template: StoredTemplate; key: string } | null>(null);
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
      setNotice(`${r.updated} updated.` + (r.notFound.length ? ` Not on SmartChat yet: ${r.notFound.join(", ")}.` : ""));
    },
    onError: (e) => setNotice(`${formatApiError(e, "Could not read templates from SmartChat.")} You can still update a status with Edit.`),
  });

  const remove = useMutation({
    mutationFn: (id: number) => storedTemplatesApi.remove(id),
    onSuccess: () => {
      invalidate();
      setDeleting(null);
    },
  });

  /** What Send opens: the stored template, or for a listed WhatsApp one its entry in the send catalogue. */
  const sendKeyFor = (t: StoredTemplate): string | null => {
    if (!t.systemManaged) return t.unsendableReason == null ? t.key : null;
    if (t.channel !== "WHATSAPP" || t.status !== "ACTIVE") return null;
    const key = t.name.replace(/^db_/, "").replace(/_v1_164$/, "");
    return catalogue.data?.some((c) => c.key === key) ? key : null;
  };
  const catalogueEntry = (key: string | null) => catalogue.data?.find((c) => c.key === key);
  const needsOne = (t: StoredTemplate, key: string | null) =>
    catalogueEntry(key)?.singleOnly ?? t.variables.some((v) => v.startsWith("in:"));

  const term = search.trim().toLowerCase();
  const all = (q.data ?? []).filter(
    (t) => term === "" || [t.label, t.name, t.body].some((v) => (v ?? "").toLowerCase().includes(term)),
  );
  const ready = all.filter((t) => t.status === "ACTIVE" && sendKeyFor(t) != null);
  const automatic = all.filter((t) => t.status === "ACTIVE" && sendKeyFor(t) == null && t.systemManaged);
  const notReady = all.filter((t) => !ready.includes(t) && !automatic.includes(t));
  // With a group chosen, only what every customer in it can actually receive is offered.
  const shown = audience
    ? ready.filter((t) => {
        const key = sendKeyFor(t);
        return fits(catalogueEntry(key)?.rule ?? t.rule, audience.group) && !needsOne(t, key);
      })
    : ready;

  const closeSend = () => {
    setSending(null);
    setTargets(null);
  };
  const startSend = (t: StoredTemplate, key: string) => {
    setSending({ template: t, key });
    if (audience) setTargets(audience.customerIds.map((id) => ({ customerId: id, label: `Customer #${id}` })));
  };

  const card = (t: StoredTemplate) => (
    <TemplateCard
      key={t.id}
      template={t}
      off={off}
      sendKey={sendKeyFor(t)}
      audience={audience}
      onEdit={() => setEditing(t)}
      onDelete={() => {
        remove.reset();
        setDeleting(t);
      }}
      onSend={startSend}
    />
  );
  const fold = (title: string, rows: StoredTemplate[]) =>
    rows.length > 0 && (
      <details className="group">
        <summary className="cursor-pointer text-sm font-semibold text-muted hover:text-navy">
          {title} ({rows.length})
        </summary>
        <div className="mt-4 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">{rows.map(card)}</div>
      </details>
    );

  return (
    <div className="space-y-9">
      {notice && (
        <p role="status" className="rounded-lg border border-line bg-white px-3 py-2 text-xs text-ink">
          {notice}
        </p>
      )}
      {q.isLoading ? (
        <Skeleton variant="table" rows={6} cols={2} className="rounded-lg border border-line bg-white" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : shown.length === 0 && (audience != null || all.length === 0) ? (
        <EmptyState title={audience ? "No templates fit this group." : "No templates match."} />
      ) : (
        SECTIONS.map((s) => {
          const rows = shown.filter((t) => (t.purpose ?? "OTHER") === s.key);
          return (
            rows.length > 0 && (
              <section key={s.key}>
                <h2 className="mb-3.5 text-[15px] font-bold text-navy">{s.title}</h2>
                <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">{rows.map(card)}</div>
              </section>
            )
          );
        })
      )}

      {audience?.capped && <p className="text-xs text-muted">Only the first 2,000 customers in this group are sent to at a time.</p>}

      {!audience && !q.isLoading && (
        <div className="flex flex-col gap-4">
          {fold("Not ready to send", notReady)}
          {fold("Sent automatically", automatic)}
          {channel === "WHATSAPP" && (
            <div>
              <button type="button" disabled={off || refresh.isPending} onClick={() => refresh.mutate()} className="inline-flex items-center gap-1.5 text-xs font-semibold text-muted hover:text-navy disabled:opacity-50">
                {refresh.isPending ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh status from SmartChat
              </button>
            </div>
          )}
        </div>
      )}

      {(adding || editing) && (
        <TemplateForm
          channel={channel}
          template={editing}
          onClose={() => {
            setEditing(null);
            onAdded();
          }}
          onSaved={() => {
            invalidate();
            setEditing(null);
            onAdded();
          }}
        />
      )}
      {deleting && (
        <Dialog open onClose={() => setDeleting(null)} aria-label="Delete template">
          <DialogHeader>
            <DialogTitle>Delete {deleting.label ?? deleting.name}?</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-ink">This removes it from this page only. It stays on {channel === "SMS" ? "DLT and Ultron" : "SmartChat"}.</p>
          {remove.error != null && <p className="mt-2 text-xs text-error-700">{formatApiError(remove.error, "Could not delete.")}</p>}
          <DialogFooter>
            <button type="button" onClick={() => setDeleting(null)} className="btn btn-sm btn-outline">
              Cancel
            </button>
            <button type="button" disabled={remove.isPending} onClick={() => remove.mutate(deleting.id)} className="btn btn-sm btn-navy disabled:opacity-50">
              Delete
            </button>
          </DialogFooter>
        </Dialog>
      )}
      {sending && !targets && (
        <RecipientPicker
          single={needsOne(sending.template, sending.key)}
          templateName={sending.template.label ?? sending.template.name}
          onCancel={closeSend}
          onPick={setTargets}
        />
      )}
      {sending && targets && <SendMessageDialog open onClose={closeSend} targets={targets} initialTemplateKey={sending.key} />}
    </div>
  );
}

function TemplateCard({
  template: t,
  off,
  sendKey,
  audience,
  onEdit,
  onDelete,
  onSend,
}: {
  template: StoredTemplate;
  off: boolean;
  sendKey: string | null;
  audience: Audience | null;
  onEdit: () => void;
  onDelete: () => void;
  onSend: (t: StoredTemplate, key: string) => void;
}) {
  const section = SECTIONS.find((s) => s.key === t.purpose) ?? SECTIONS[SECTIONS.length - 1];
  const empty = audience != null && !audience.count;
  const ready = t.status === "ACTIVE" && sendKey != null;
  return (
    <article className="flex flex-col gap-2 rounded-[10px] border border-line bg-white px-3.5 py-3">
      <div className="flex h-6 items-center justify-between">
        <span className={`rounded-md px-2 py-[3px] text-[11px] font-bold ${section.pill}`}>{section.title.toUpperCase()}</span>
        <span className="-mr-2 flex">
          <button type="button" aria-label={`Edit ${t.label ?? t.name}`} title="Edit" disabled={off} onClick={onEdit} className="grid h-[30px] w-[30px] place-items-center text-muted hover:text-navy disabled:opacity-50">
            <Pencil size={14} />
          </button>
          {!t.systemManaged && (
            <button type="button" aria-label={`Delete ${t.label ?? t.name}`} title="Delete" disabled={off} onClick={onDelete} className="grid h-[30px] w-[30px] place-items-center text-muted hover:text-error-700 disabled:opacity-50">
              <Trash2 size={14} />
            </button>
          )}
        </span>
      </div>
      <div className="min-w-0 flex-1">
        <h3 className="mb-0.5 break-words text-[15px] font-bold text-navy">{t.label ?? t.name}</h3>
        <p title={plainText(t)} className="line-clamp-2 text-[13px] leading-[1.4] text-muted">
          {plainText(t)}
        </p>
      </div>
      {!ready && (
        <p className="text-xs text-muted">
          {statusLabel(t.channel, t.status)}
          {t.rejectionReason && `: ${t.rejectionReason}`}
          {t.unsendableReason && !t.rejectionReason && <span className="block">{t.unsendableReason}</span>}
        </p>
      )}
      <div className="flex justify-end">
        <button
          type="button"
          disabled={off || sendKey == null || empty}
          onClick={() => sendKey && onSend(t, sendKey)}
          className="h-[34px] rounded-[7px] bg-navy px-3.5 text-[13px] font-bold text-white disabled:opacity-50"
        >
          {audience ? `Send to ${audience.count ?? "…"}` : "Send"}
        </button>
      </div>
    </article>
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
  const [purpose, setPurpose] = React.useState<string>(template?.purpose ?? "");
  const [label, setLabel] = React.useState(template?.label ?? "");
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
        purpose,
        label,
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
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="field">
            <label htmlFor="tpl-label">Display name (optional)</label>
            <input id="tpl-label" value={label} maxLength={120} onChange={(e) => setLabel(e.target.value)} />
          </div>
          <Select
            label="Category"
            required
            value={purpose}
            onChange={(e) => setPurpose(e.target.value)}
            options={[{ value: "", label: "Choose…" }, ...SECTIONS.map((c) => ({ value: c.key, label: c.title }))]}
          />
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
          label="Message type"
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
          disabled={save.isPending || name.trim() === "" || purpose === "" || !typedOk}
          onClick={() => save.mutate()}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {save.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Save
        </button>
      </DialogFooter>
    </Dialog>
  );
}
