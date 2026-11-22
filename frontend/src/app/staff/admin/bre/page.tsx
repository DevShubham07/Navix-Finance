"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Badge, ErrorState, Skeleton, toast } from "@/components/ui";
import { ToggleRow } from "@/components/ui/toggle-row";
import { InfoTooltip } from "@/components/ui/tooltip";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { breRulesApi, type BreRuleGroup, type BreRuleView, type BreThresholdView } from "@/lib/api/applications";
import { formatDateTime } from "@/lib/utils";

const SECTIONS: { group: BreRuleGroup; title: string; intro: string }[] = [
  { group: "AUTO", title: "Auto-decision rules", intro: "These reject or approve applications on their own. Switching one off sends those cases to a person instead." },
  {
    group: "ADVISORY",
    title: "Customer 360 advisory checks",
    intro: "Flag-only: these never reject anyone. Switching one off hides it from the Customer 360 BRE tab.",
  },
  { group: "REVIEW", title: "Review-only checks", intro: "Always sent to a human for a decision. Read-only." },
  { group: "ALWAYS", title: "Always enforced", intro: "Fixed rules that cannot be switched off. Read-only, except the thresholds shown." },
  { group: "GATE", title: "Intake gates", intro: "Switches that stop new applications at the door." },
];

const UNIT: Record<BreThresholdView["unit"], string> = { days: "days", score: "pts", percent: "%" };

function ThresholdEditor({ t, onSaved }: { t: BreThresholdView; onSaved: (t: BreThresholdView) => void }) {
  const [text, setText] = React.useState(String(t.value));
  React.useEffect(() => setText(String(t.value)), [t.value]);
  const n = Number(text);
  const valid = text.trim() !== "" && Number.isFinite(n) && n >= t.min && n <= t.max;
  const save = useMutation({
    mutationFn: () => breRulesApi.setThreshold(t.key, n),
    onSuccess: (r) => {
      onSaved(r);
      toast.success(`${t.label} set to ${r.value} ${UNIT[t.unit]}`);
    },
    onError: (e) => toast.error(errMessage(e)),
  });
  return (
    <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
      <label className="text-muted" htmlFor={`th-${t.key}`}>{t.label}</label>
      <input
        id={`th-${t.key}`}
        type="number"
        inputMode="decimal"
        min={t.min}
        max={t.max}
        value={text}
        onChange={(e) => setText(e.target.value)}
        aria-invalid={!valid}
        className="w-20 rounded border border-line bg-white px-2 py-1 text-navy"
      />
      <span className="text-muted">{UNIT[t.unit]}</span>
      <span className="text-muted">default {t.defaultValue} · allowed {t.min}–{t.max}</span>
      <button className="btn btn-navy" disabled={!valid || n === t.value || save.isPending} onClick={() => save.mutate()}>
        Save
      </button>
      {!valid && text.trim() !== "" && <span className="text-error-700">Enter {t.min}–{t.max}</span>}
    </div>
  );
}

function RuleRow({ rule, onRule, onThreshold }: { rule: BreRuleView; onRule: (r: BreRuleView) => void; onThreshold: (t: BreThresholdView) => void }) {
  const [confirmOff, setConfirmOff] = React.useState(false);
  const toggle = useMutation({
    mutationFn: (enabled: boolean) => breRulesApi.setEnabled(rule.key, enabled),
    onSuccess: (r) => {
      setConfirmOff(false);
      onRule(r);
      toast.success(`${r.title}: ${r.enabled ? "switched on" : "switched off"}`);
    },
    onError: (e) => {
      setConfirmOff(false);
      toast.error(errMessage(e));
    },
  });
  const tip = (
    <span>
      <strong>Outcome:</strong> {rule.outcome}
      <br />
      <strong>Source:</strong> {rule.source}
    </span>
  );
  const canToggle = rule.toggleable && rule.enabled != null;
  return (
    <li className="border-t border-line py-3 first:border-t-0">
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          {canToggle ? (
            <ToggleRow
              label={rule.title}
              description={rule.checks}
              on={rule.enabled === true}
              disabled={toggle.isPending}
              onChange={(on) => (on || rule.group !== "AUTO" ? toggle.mutate(on) : setConfirmOff(true))}
            />
          ) : (
            <div>
              <div className="flex items-center gap-2 text-sm font-medium text-navy">
                {rule.title}
                {rule.group === "GATE" && rule.enabled != null && (
                  <Badge variant={rule.enabled ? "success" : "warning"} size="sm">{rule.enabled ? "On" : "Off"}</Badge>
                )}
              </div>
              <p className="mt-0.5 text-xs text-muted">{rule.checks}</p>
              {rule.group === "GATE" && (
                <Link href="/staff/admin/settings" className="mt-1 inline-block text-xs text-navy underline">
                  Change in Settings
                </Link>
              )}
            </div>
          )}
        </div>
        <InfoTooltip content={tip} label={`About ${rule.title}`} />
      </div>
      {confirmOff && (
        <div role="alertdialog" aria-label={`Switch off ${rule.title}`} className="mt-3 rounded border border-warning-500 bg-warning-50 p-3 text-sm">
          <p className="font-semibold text-ink">Switch off “{rule.title}”?</p>
          <p className="mt-1 text-xs text-muted">
            Customers will no longer be rejected or auto-approved by this rule. Their applications go to a person for review instead.
          </p>
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
      {rule.thresholds.map((t) => (
        <ThresholdEditor key={t.key} t={t} onSaved={onThreshold} />
      ))}
      {rule.updatedBy && rule.updatedAt && (
        <p className="mt-2 text-xs text-muted">
          Last changed by {rule.updatedBy} · {formatDateTime(rule.updatedAt)}
        </p>
      )}
    </li>
  );
}

/** Admin · BRE rules — every business rule in one place: switches, thresholds, read-only policy. ADMIN only. */
export default function AdminBrePage() {
  const myRole = useStaffMe().data?.role;
  const can = useCan();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["bre-rules"], queryFn: breRulesApi.list });

  const patchRule = (r: BreRuleView) =>
    qc.setQueryData<BreRuleView[]>(["bre-rules"], (old) => old?.map((x) => (x.key === r.key ? r : x)));
  const patchThreshold = (t: BreThresholdView) =>
    qc.setQueryData<BreRuleView[]>(["bre-rules"], (old) =>
      old?.map((x) => ({ ...x, thresholds: x.thresholds.map((y) => (y.key === t.key ? t : y)) })),
    );

  if (myRole && !can("staff:manage")) return <NoAccessNotice message="Admin access only." />;

  return (
    <div>
      <PageHeader title="BRE rules" subtitle="The business rules behind application decisions — what is switched on, and the limits they use." />
      {q.isLoading ? (
        <Skeleton variant="line" rows={6} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        SECTIONS.map((s) => {
          const rules = (q.data ?? []).filter((r) => r.group === s.group);
          if (rules.length === 0) return null;
          return (
            <section key={s.group} className="mb-6 max-w-2xl rounded border border-line bg-white p-4 shadow-sm sm:p-5">
              <div className="text-sm font-semibold text-navy">{s.title}</div>
              <p className="mb-2 text-xs text-muted">{s.intro}</p>
              <ul>
                {rules.map((r) => (
                  <RuleRow key={r.key} rule={r} onRule={patchRule} onThreshold={patchThreshold} />
                ))}
              </ul>
            </section>
          );
        })
      )}
    </div>
  );
}
