"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Ban, Eye, Gauge, Lock, ShieldAlert, DoorClosed, type LucideIcon } from "lucide-react";
import { ErrorState, Skeleton, toast } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { breRulesApi, type BreRuleGroup, type BreRuleView, type BreThresholdView } from "@/lib/api/applications";

const PINCODE_RULE = "pincode-blocked-reject";
/** Every 6-digit pin code in free text or a CSV (header row and other columns are ignored). */
const pinsIn = (text: string) => [...new Set(text.match(/[1-9]\d{5}/g) ?? [])];
import { formatDateTime } from "@/lib/utils";

/** One colour family per group, so a rule's tab, banner, stripe and code chip all read as one set. */
type Tone = { tab: string; bar: string; stripe: string; soft: string; text: string; chip: string; switchOn: string };

const GROUPS: {
  group: BreRuleGroup;
  letter: string;
  label: string;
  Icon: LucideIcon;
  intro: string;
  tone: Tone;
}[] = [
  {
    group: "AUTO",
    letter: "A",
    label: "Auto decisions",
    Icon: Ban,
    intro: "Reject or approve an application on their own. Each card says what happens when it is switched off.",
    tone: { tab: "text-rose-700", bar: "bg-rose-500", stripe: "bg-rose-500", soft: "bg-rose-50 border-rose-200", text: "text-rose-800", chip: "bg-rose-100 text-rose-700", switchOn: "bg-rose-600" },
  },
  {
    group: "ADVISORY",
    letter: "B",
    label: "Advisory checks",
    Icon: Gauge,
    intro: "Customer 360 BRE pass/flag checks for reviewers — they never reject. Off = hidden from the BRE tab.",
    tone: { tab: "text-violet-700", bar: "bg-violet-500", stripe: "bg-violet-500", soft: "bg-violet-50 border-violet-200", text: "text-violet-800", chip: "bg-violet-100 text-violet-700", switchOn: "bg-violet-600" },
  },
  {
    group: "REVIEW",
    letter: "C",
    label: "Review only",
    Icon: Eye,
    intro: "A failed or unclear result goes to a credit reviewer; the borrower is never declined by it.",
    tone: { tab: "text-amber-700", bar: "bg-amber-500", stripe: "bg-amber-500", soft: "bg-amber-50 border-amber-200", text: "text-amber-900", chip: "bg-amber-100 text-amber-800", switchOn: "bg-amber-600" },
  },
  {
    group: "ALWAYS",
    letter: "D",
    label: "Always enforced",
    Icon: Lock,
    intro: "Fixed platform rules that cannot be switched off. Only the limits shown are editable.",
    tone: { tab: "text-sky-700", bar: "bg-sky-500", stripe: "bg-sky-500", soft: "bg-sky-50 border-sky-200", text: "text-sky-900", chip: "bg-sky-100 text-sky-700", switchOn: "bg-sky-600" },
  },
  {
    group: "GATE",
    letter: "E",
    label: "Intake gates",
    Icon: DoorClosed,
    intro: "Stop new applications at the door. Changed in Settings — shown here for the full picture.",
    tone: { tab: "text-teal-700", bar: "bg-teal-500", stripe: "bg-teal-500", soft: "bg-teal-50 border-teal-200", text: "text-teal-900", chip: "bg-teal-100 text-teal-700", switchOn: "bg-teal-600" },
  },
];

const UNIT: Record<BreThresholdView["unit"], string> = { days: "days", score: "pts", percent: "%" };

/** The catalog's outcome text carries a "When off, …" sentence — the honest confirm copy for that rule. */
const whenOff = (r: BreRuleView) => r.outcome.match(/When off, (.*)$/)?.[1] ?? "this rule is skipped entirely.";

function Switch({ on, label, disabled, color, onChange }: { on: boolean; label: string; disabled?: boolean; color: string; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!on)}
      className={`relative h-6 w-11 flex-shrink-0 rounded-full transition-colors disabled:opacity-50 ${on ? color : "bg-neutral-300"}`}
    >
      <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all ${on ? "left-[22px]" : "left-0.5"}`} />
    </button>
  );
}

function StatusPill({ rule }: { rule: BreRuleView }) {
  const [label, cls] =
    rule.group === "REVIEW"
      ? ["Review", "bg-amber-100 text-amber-800"]
      : rule.group === "ALWAYS"
        ? ["Always on", "bg-sky-100 text-sky-800"]
        : rule.enabled
          ? ["On", "bg-emerald-100 text-emerald-800"]
          : ["Off", "bg-neutral-200 text-neutral-700"];
  return <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide ${cls}`}>{label}</span>;
}

function ThresholdEditor({ t, tone, onSaved }: { t: BreThresholdView; tone: Tone; onSaved: (t: BreThresholdView) => void }) {
  const [text, setText] = React.useState(String(t.value));
  React.useEffect(() => setText(String(t.value)), [t.value]);
  const n = Number(text);
  const valid = text.trim() !== "" && Number.isInteger(n) && n >= t.min && n <= t.max;
  const dirty = valid && n !== t.value;
  const save = useMutation({
    mutationFn: () => breRulesApi.setThreshold(t.key, n),
    onSuccess: (r) => {
      onSaved(r);
      toast.success(`${t.label} set to ${r.value} ${UNIT[t.unit]}`);
    },
    onError: (e) => toast.error(errMessage(e)),
  });
  return (
    <div className={`flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-xs ${tone.soft}`}>
      <label htmlFor={`th-${t.key}`} className={`font-semibold ${tone.text}`}>
        {t.label}
      </label>
      <input
        id={`th-${t.key}`}
        type="number"
        inputMode="numeric"
        min={t.min}
        max={t.max}
        value={text}
        onChange={(e) => setText(e.target.value)}
        aria-invalid={!valid}
        className={`w-20 rounded border bg-white px-2 py-1 font-mono text-navy ${valid ? "border-line" : "border-error-500"}`}
      />
      <span className="text-muted">{UNIT[t.unit]}</span>
      <span className="text-muted">
        default {t.defaultValue} · {t.min}–{t.max}
      </span>
      {dirty && (
        <button className="btn btn-navy ml-auto" disabled={save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? "Saving…" : "Save"}
        </button>
      )}
      {!valid && text.trim() !== "" && <span className="text-error-700">Enter a whole number {t.min}–{t.max}</span>}
    </div>
  );
}

/** ADMIN editor for the blocked pin code list (BRE rule pincode-blocked-reject). */
function PincodeListEditor({ tone }: { tone: Tone }) {
  const qc = useQueryClient();
  const [q, setQ] = React.useState("");
  const [addText, setAddText] = React.useState("");
  const [upload, setUpload] = React.useState<{ name: string; pins: string[] } | null>(null);
  const list = useQuery({
    queryKey: ["bre-pincodes", q],
    queryFn: () => breRulesApi.searchPincodes(q),
    placeholderData: (prev) => prev,
  });
  const refresh = () => void qc.invalidateQueries({ queryKey: ["bre-pincodes"] });
  const add = useMutation({
    mutationFn: (pins: string[]) => breRulesApi.addPincodes(pins),
    onSuccess: (r, pins) => {
      setAddText("");
      refresh();
      toast.success(`${pins.length} pin code(s) blocked · ${r.total} on the list`);
    },
    onError: (e) => toast.error(errMessage(e)),
  });
  const remove = useMutation({
    mutationFn: (pin: string) => breRulesApi.removePincode(pin),
    onSuccess: (r, pin) => {
      refresh();
      toast.success(`${pin} unblocked · ${r.total} on the list`);
    },
    onError: (e) => toast.error(errMessage(e)),
  });
  const replace = useMutation({
    mutationFn: (pins: string[]) => breRulesApi.replacePincodes(pins),
    onSuccess: (r) => {
      setUpload(null);
      refresh();
      toast.success(`List replaced · ${r.total} pin codes`);
    },
    onError: (e) => toast.error(errMessage(e)),
  });
  const toAdd = pinsIn(addText);

  return (
    <details className={`rounded-md border px-3 py-2 text-xs ${tone.soft}`}>
      <summary className={`cursor-pointer select-none font-semibold ${tone.text}`}>
        Blocked pin codes{list.data ? ` · ${list.data.total.toLocaleString("en-IN")}` : ""}
      </summary>
      <div className="mt-3 flex flex-col gap-3">
        <div>
          <label htmlFor="pin-search" className="font-semibold text-navy">Search</label>
          <input
            id="pin-search"
            inputMode="numeric"
            placeholder="Type a pin code or its first digits"
            value={q}
            onChange={(e) => setQ(e.target.value.replace(/\D/g, "").slice(0, 6))}
            className="mt-1 w-full rounded border border-line bg-white px-2 py-1 font-mono text-navy"
          />
          {list.error ? (
            <p className="mt-1 text-error-700">{errMessage(list.error)}</p>
          ) : (
            <div className="mt-2 flex max-h-40 flex-wrap gap-1.5 overflow-y-auto">
              {list.data?.matches.map((pin) => (
                <span key={pin} className="inline-flex items-center gap-1 rounded bg-white px-1.5 py-0.5 font-mono text-navy shadow-sm">
                  {pin}
                  <button
                    type="button"
                    aria-label={`Unblock ${pin}`}
                    disabled={remove.isPending}
                    onClick={() => remove.mutate(pin)}
                    className="text-muted hover:text-error-700"
                  >
                    ×
                  </button>
                </span>
              ))}
              {list.data && list.data.matches.length === 0 && <span className="text-muted">No blocked pin code matches.</span>}
              {list.data && list.data.matches.length === 50 && <span className="text-muted">First 50 shown. Type more digits to narrow the list.</span>}
            </div>
          )}
        </div>

        <div>
          <label htmlFor="pin-add" className="font-semibold text-navy">Block more</label>
          <div className="mt-1 flex gap-2">
            <input
              id="pin-add"
              placeholder="e.g. 110001, 400001"
              value={addText}
              onChange={(e) => setAddText(e.target.value)}
              className="w-full rounded border border-line bg-white px-2 py-1 font-mono text-navy"
            />
            <button className="btn btn-navy" disabled={!toAdd.length || add.isPending} onClick={() => add.mutate(toAdd)}>
              {add.isPending ? "Adding…" : `Add${toAdd.length ? ` ${toAdd.length}` : ""}`}
            </button>
          </div>
        </div>

        <div>
          <label htmlFor="pin-csv" className="font-semibold text-navy">Replace the whole list from a CSV</label>
          <input
            id="pin-csv"
            type="file"
            accept=".csv,text/csv,text/plain"
            className="mt-1 block w-full text-xs"
            onChange={async (e) => {
              const f = e.target.files?.[0];
              e.target.value = "";
              if (!f) return;
              const pins = pinsIn(await f.text());
              if (!pins.length) toast.error("No 6-digit pin codes found in that file");
              else setUpload({ name: f.name, pins });
            }}
          />
          {upload && (
            <div role="alertdialog" aria-label="Replace blocked pin codes" className="mt-2 rounded-md border border-warning-500 bg-warning-50 p-3">
              <p className="font-semibold text-ink">
                Replace all {list.data?.total.toLocaleString("en-IN") ?? ""} blocked pin codes with the{" "}
                {upload.pins.length.toLocaleString("en-IN")} in {upload.name}?
              </p>
              <p className="mt-1 text-muted">Pin codes that are not in the file will be unblocked.</p>
              <div className="mt-2 flex gap-2">
                <button className="btn btn-navy" disabled={replace.isPending} onClick={() => replace.mutate(upload.pins)}>
                  {replace.isPending ? "Replacing…" : "Yes, replace"}
                </button>
                <button className="btn" disabled={replace.isPending} onClick={() => setUpload(null)}>
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </details>
  );
}

function RuleCard({
  rule,
  code,
  tone,
  onRule,
  onThreshold,
}: {
  rule: BreRuleView;
  code: string;
  tone: Tone;
  onRule: (r: BreRuleView) => void;
  onThreshold: (t: BreThresholdView) => void;
}) {
  const [confirmOff, setConfirmOff] = React.useState(false);
  const toggle = useMutation({
    mutationFn: (enabled: boolean) => breRulesApi.setEnabled(rule.key, enabled),
    onSuccess: (r) => {
      setConfirmOff(false);
      onRule(r);
      toast.success(`${r.title}: switched ${r.enabled ? "on" : "off"}`);
    },
    onError: (e) => {
      setConfirmOff(false);
      toast.error(errMessage(e));
    },
  });
  const canToggle = rule.toggleable && rule.enabled != null;
  const off = rule.enabled === false && rule.group !== "GATE";

  return (
    <article className={`relative overflow-hidden rounded-lg border border-line bg-white shadow-sm transition ${off ? "bg-neutral-50" : "hover:shadow-md"}`}>
      <span aria-hidden className={`absolute inset-y-0 left-0 w-1.5 ${off ? "bg-neutral-300" : tone.stripe}`} />
      <div className="flex flex-col gap-3 p-4 pl-5">
        <div className="flex items-start gap-3">
          <span className={`mt-0.5 rounded px-1.5 py-0.5 font-mono text-[11px] font-bold ${off ? "bg-neutral-200 text-neutral-600" : tone.chip}`}>{code}</span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className={`text-sm font-semibold ${off ? "text-neutral-500" : "text-navy"}`}>{rule.title}</h3>
              <StatusPill rule={rule} />
            </div>
            <p className="mt-1 line-clamp-1 text-xs text-muted" title={rule.checks}>
              {rule.checks}
            </p>
          </div>
          {canToggle && (
            <Switch
              on={rule.enabled === true}
              label={`${rule.title}: ${rule.enabled ? "on" : "off"}`}
              disabled={toggle.isPending}
              color={tone.switchOn}
              onChange={(on) => (on || rule.group !== "AUTO" ? toggle.mutate(on) : setConfirmOff(true))}
            />
          )}
          {rule.group === "GATE" && (
            <Link href="/staff/admin/settings" className="whitespace-nowrap text-xs font-medium text-teal-700 underline">
              Change in Settings
            </Link>
          )}
        </div>

        {confirmOff && (
          <div role="alertdialog" aria-label={`Switch off ${rule.title}`} className="rounded-md border border-warning-500 bg-warning-50 p-3 text-sm">
            <p className="font-semibold text-ink">Switch off “{rule.title}”?</p>
            <p className="mt-1 text-xs text-muted">When off, {whenOff(rule)}</p>
            <div className="mt-3 flex gap-2">
              <button className="btn btn-navy" disabled={toggle.isPending} onClick={() => toggle.mutate(false)}>
                Yes, switch off
              </button>
              <button className="btn" disabled={toggle.isPending} onClick={() => setConfirmOff(false)}>
                Cancel
              </button>
            </div>
          </div>
        )}

        {rule.thresholds.length > 0 && (
          <div className="flex flex-col gap-2">
            {rule.thresholds.map((t) => (
              <ThresholdEditor key={t.key} t={t} tone={tone} onSaved={onThreshold} />
            ))}
          </div>
        )}

        {rule.key === PINCODE_RULE && <PincodeListEditor tone={tone} />}

        <details className="group text-xs">
          <summary className="cursor-pointer select-none font-medium text-navy marker:text-muted">What happens when it fires</summary>
          <div className="mt-2 space-y-1 border-l-2 border-line pl-3 text-muted">
            <p>{rule.outcome}</p>
            <p className="font-mono text-[11px]">{rule.source}</p>
            {rule.updatedBy && rule.updatedAt && (
              <p>
                Last changed by {rule.updatedBy} · {formatDateTime(rule.updatedAt)}
              </p>
            )}
          </div>
        </details>
      </div>
    </article>
  );
}

/** Admin · BRE rules — every business rule in one place: switches, limits, read-only policy. ADMIN only. */
export default function AdminBrePage() {
  const myRole = useStaffMe().data?.role;
  const can = useCan();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["bre-rules"], queryFn: breRulesApi.list });
  const [active, setActive] = React.useState<BreRuleGroup>("AUTO");
  const tabRefs = React.useRef<(HTMLButtonElement | null)[]>([]);

  const patchRule = (r: BreRuleView) =>
    qc.setQueryData<BreRuleView[]>(["bre-rules"], (old) => old?.map((x) => (x.key === r.key ? r : x)));
  const patchThreshold = (t: BreThresholdView) =>
    qc.setQueryData<BreRuleView[]>(["bre-rules"], (old) =>
      old?.map((x) => ({ ...x, thresholds: x.thresholds.map((y) => (y.key === t.key ? t : y)) })),
    );

  if (myRole && !can("staff:manage")) return <NoAccessNotice message="Admin access only." />;

  const rules = q.data ?? [];
  const byGroup = (g: BreRuleGroup) => rules.filter((r) => r.group === g);
  const switchable = rules.filter((r) => r.toggleable);
  const offCount = switchable.filter((r) => r.enabled === false).length;
  const limits = rules.reduce((n, r) => n + r.thresholds.length, 0);
  const current = GROUPS.find((g) => g.group === active) ?? GROUPS[0];

  const onTabKey = (e: React.KeyboardEvent, i: number) => {
    const next = e.key === "ArrowRight" ? i + 1 : e.key === "ArrowLeft" ? i - 1 : null;
    if (next == null) return;
    const j = (next + GROUPS.length) % GROUPS.length;
    setActive(GROUPS[j].group);
    tabRefs.current[j]?.focus();
  };

  return (
    <div>
      <PageHeader title="BRE rules" subtitle="Every rule behind an application decision — what is switched on, and the limits it uses." />
      {q.isLoading ? (
        <Skeleton variant="line" rows={8} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <>
          <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
            {[
              { label: "Rules", value: rules.length, cls: "from-navy to-[#1d3d63] text-white" },
              { label: "Switched on", value: switchable.length - offCount, cls: "from-emerald-500 to-emerald-700 text-white" },
              { label: "Switched off", value: offCount, cls: offCount ? "from-rose-500 to-rose-700 text-white" : "from-neutral-200 to-neutral-300 text-neutral-700" },
              { label: "Editable limits", value: limits, cls: "from-violet-500 to-violet-700 text-white" },
            ].map((s) => (
              <div key={s.label} className={`rounded-lg bg-gradient-to-br px-4 py-3 shadow-sm ${s.cls}`}>
                <div className="text-[11px] font-semibold uppercase tracking-wide opacity-80">{s.label}</div>
                <div className="font-mono text-2xl font-bold">{s.value}</div>
              </div>
            ))}
          </div>

          <div role="tablist" aria-label="Rule groups" className="flex overflow-x-auto rounded-t-lg border border-b-0 border-line bg-white">
            {GROUPS.map((g, i) => {
              const list = byGroup(g.group);
              const on = list.filter((r) => r.toggleable && r.enabled).length;
              const toggleable = list.filter((r) => r.toggleable).length;
              const selected = g.group === active;
              return (
                <button
                  key={g.group}
                  ref={(el) => {
                    tabRefs.current[i] = el;
                  }}
                  role="tab"
                  id={`tab-${g.group}`}
                  aria-selected={selected}
                  aria-controls={`panel-${g.group}`}
                  tabIndex={selected ? 0 : -1}
                  onClick={() => setActive(g.group)}
                  onKeyDown={(e) => onTabKey(e, i)}
                  className={`relative flex min-w-[150px] flex-1 items-center gap-2 border-r border-line px-4 py-3 text-left last:border-r-0 ${
                    selected ? "bg-white" : "bg-neutral-50 hover:bg-white"
                  }`}
                >
                  <span aria-hidden className={`absolute inset-x-0 top-0 h-1 ${selected ? g.tone.bar : "bg-transparent"}`} />
                  <g.Icon aria-hidden className={`h-4 w-4 flex-shrink-0 ${selected ? g.tone.tab : "text-muted"}`} />
                  <span className="min-w-0">
                    <span className={`block text-sm font-semibold ${selected ? g.tone.tab : "text-ink"}`}>{g.label}</span>
                    <span className="block text-[11px] text-muted">
                      {list.length} rules{toggleable ? ` · ${on} on` : ""}
                    </span>
                  </span>
                </button>
              );
            })}
          </div>

          <section
            role="tabpanel"
            id={`panel-${current.group}`}
            aria-labelledby={`tab-${current.group}`}
            className="rounded-b-lg border border-line bg-neutral-50 p-3 sm:p-4"
          >
            <div className={`mb-4 flex items-start gap-3 rounded-md border px-4 py-3 ${current.tone.soft}`}>
              <current.Icon aria-hidden className={`mt-0.5 h-5 w-5 flex-shrink-0 ${current.tone.tab}`} />
              <div>
                <div className={`text-sm font-semibold ${current.tone.text}`}>
                  {current.letter} · {current.label}
                </div>
                <p className={`text-xs ${current.tone.text} opacity-90`}>{current.intro}</p>
              </div>
              {current.group === "AUTO" && (
                <ShieldAlert aria-hidden className="ml-auto hidden h-5 w-5 text-rose-400 sm:block" />
              )}
            </div>
            <div className="grid gap-3 lg:grid-cols-2">
              {byGroup(current.group).map((r, i) => (
                <RuleCard key={r.key} rule={r} code={`${current.letter}${i + 1}`} tone={current.tone} onRule={patchRule} onThreshold={patchThreshold} />
              ))}
            </div>
          </section>
        </>
      )}
    </div>
  );
}
