"use client";

/**
 * Admin "Send message" dialog: pick an approved template, channel(s) and inputs, preview the first
 * recipient, then send. There is NO batch endpoint — like the queue bulk actions
 * (`pipeline/bulk-actions.tsx`) it loops the per-recipient `customerMessagesApi.send`, up to
 * {@link DEFAULT_BULK_CONCURRENCY} at once, and one failure never aborts the rest.
 */

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ErrorState, Select } from "@/components/ui";
import {
  ApplicationApiError,
  customerMessagesApi,
  type MessageChannel,
  type MessagePreview,
  type MessageSendResult,
  type MessageTarget,
  type MessageTemplate,
} from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { runWithConcurrency, DEFAULT_BULK_CONCURRENCY } from "@/lib/staff/queue-bulk";

export interface MessageTargetItem {
  applicationId?: number;
  customerId?: number;
  /** Shown in the results list. */
  label: string;
}

/** The discount percent the backend accepts. */
export const PERCENT_OPTIONS = [1, 2, 3, 4, 5, 6, 7] as const;
const CHANNEL_LABEL: Record<MessageChannel, string> = { SMS: "SMS", WHATSAPP: "WhatsApp", EMAIL: "Email" };

function toTarget(t: MessageTargetItem): MessageTarget {
  return t.applicationId != null ? { applicationId: t.applicationId } : { customerId: t.customerId as number };
}

/** Plain wording for the two refusals that apply to every recipient alike. */
function describe(e: unknown): string {
  if (e instanceof ApplicationApiError) {
    if (e.code === "FEATURE_DISABLED") return "Customer messaging is switched off (admin-messaging flag).";
    if (e.code === "OUTSIDE_SEND_HOURS") return "Messages can only be sent between 08:00 and 19:00 IST.";
  }
  return formatApiError(e, "Send failed.");
}

type Row = { label: string; result?: MessageSendResult; error?: string };

export function SendMessageDialog({
  open,
  onClose,
  targets,
  initialTemplateKey,
}: {
  open: boolean;
  onClose: () => void;
  targets: MessageTargetItem[];
  /** Opens with this template already chosen (the Templates page's Send). */
  initialTemplateKey?: string;
}) {
  const multi = targets.length > 1;
  const tq = useQuery({
    queryKey: ["customer-message-templates"],
    queryFn: () => customerMessagesApi.templates(),
    enabled: open,
    staleTime: 5 * 60_000,
  });
  const templates = React.useMemo(
    () => (tq.data ?? []).filter((t) => !(multi && t.singleOnly)),
    [tq.data, multi],
  );

  const [key, setKey] = React.useState("");
  const [channels, setChannels] = React.useState<MessageChannel[]>([]);
  const [inputs, setInputs] = React.useState<Record<string, string>>({});
  const [confirmed, setConfirmed] = React.useState(false);
  const [preview, setPreview] = React.useState<MessagePreview | null>(null);
  const [busy, setBusy] = React.useState<"preview" | "send" | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [progress, setProgress] = React.useState({ done: 0, total: 0 });
  const [rows, setRows] = React.useState<Row[] | null>(null);

  React.useEffect(() => {
    if (!open) return;
    setKey("");
    setChannels([]);
    setInputs({});
    setConfirmed(false);
    setPreview(null);
    setError(null);
    setRows(null);
  }, [open]);

  React.useEffect(() => {
    const t = open && initialTemplateKey ? templates.find((x) => x.key === initialTemplateKey) : undefined;
    if (t) {
      setKey(t.key);
      setChannels([...t.channels]);
    }
  }, [open, initialTemplateKey, templates]);

  const tpl: MessageTemplate | undefined = templates.find((t) => t.key === key);

  /** Any edit invalidates the preview: Send must reflect exactly what was previewed. */
  const edit = (fn: () => void) => {
    fn();
    setPreview(null);
    setError(null);
  };
  const pick = (k: string) =>
    edit(() => {
      const t = templates.find((x) => x.key === k);
      setKey(k);
      setChannels(t && t.channels.length === 1 ? [t.channels[0]] : []);
      setInputs({});
      setConfirmed(false);
    });
  const toggleChannel = (c: MessageChannel) =>
    edit(() => setChannels((cs) => (cs.includes(c) ? cs.filter((x) => x !== c) : [...cs, c])));

  const inputsOk = !!tpl && tpl.inputs.every((i) => (inputs[i.name] ?? "").trim() !== "");
  const formOk = !!tpl && channels.length > 0 && inputsOk;
  const needsConfirm = !!tpl?.confirmText;
  const request = () => ({ templateKey: key, channels, inputs });

  async function runPreview() {
    setBusy("preview");
    setError(null);
    try {
      setPreview(await customerMessagesApi.preview({ target: toTarget(targets[0]), ...request() }));
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(null);
    }
  }

  async function runSend() {
    const out: Row[] = targets.map((t) => ({ label: t.label }));
    setBusy("send");
    setError(null);
    setProgress({ done: 0, total: targets.length });
    await runWithConcurrency(
      targets.map((_, i) => i),
      async (i) => {
        try {
          out[i].result = await customerMessagesApi.send({
            target: toTarget(targets[i]),
            ...request(),
            confirmed,
          });
        } catch (e) {
          out[i].error = describe(e);
        }
      },
      { concurrency: DEFAULT_BULK_CONCURRENCY, onProgress: (done, total) => setProgress({ done, total }) },
    );
    setRows(out);
    setBusy(null);
  }

  const count = targets.length;
  return (
    <Dialog open={open} onClose={busy ? () => {} : onClose} aria-label="Send message" size="lg">
      <DialogHeader>
        <DialogTitle>
          Send message to {count} customer{count === 1 ? "" : "s"}
        </DialogTitle>
      </DialogHeader>

      {rows ? (
        <>
          <ResultList rows={rows} />
          <DialogFooter>
            <button type="button" onClick={onClose} className="btn btn-sm btn-navy">
              Done
            </button>
          </DialogFooter>
        </>
      ) : tq.isLoading ? (
        <p className="text-sm text-muted">Loading templates…</p>
      ) : tq.error ? (
        <ErrorState error={tq.error} title="Couldn't load templates." onRetry={() => void tq.refetch()} className="py-4" />
      ) : (
        <div className="space-y-3">
          <Select label="Template" value={key} onChange={(e) => pick(e.target.value)}>
            <option value="" disabled>
              Select template…
            </option>
            {templates.map((t) => (
              <option key={t.key} value={t.key}>
                {t.label}
                {t.category === "MARKETING" ? " (marketing)" : ""}
              </option>
            ))}
          </Select>
          {multi && <p className="text-xs text-muted">Templates that need per-customer details are hidden for multiple recipients.</p>}

          {tpl && (
            <>
              <fieldset className="flex flex-wrap items-center gap-4">
                <legend className="mb-1 text-xs font-semibold text-ink">Channels</legend>
                {tpl.channels.map((c) => (
                  <label key={c} className="flex items-center gap-1.5 text-sm">
                    <input type="checkbox" checked={channels.includes(c)} onChange={() => toggleChannel(c)} />
                    {CHANNEL_LABEL[c]}
                  </label>
                ))}
              </fieldset>

              {tpl.inputs.map((i) =>
                i.type === "PERCENT" ? (
                  <Select
                    key={i.name}
                    label={i.label}
                    value={inputs[i.name] ?? ""}
                    onChange={(e) => edit(() => setInputs((s) => ({ ...s, [i.name]: e.target.value })))}
                  >
                    <option value="" disabled>
                      Select…
                    </option>
                    {PERCENT_OPTIONS.map((p) => (
                      <option key={p} value={String(p)}>
                        {p}%
                      </option>
                    ))}
                  </Select>
                ) : (
                  <div key={i.name} className="field">
                    <label htmlFor={`msg-${i.name}`}>{i.label}</label>
                    <input
                      id={`msg-${i.name}`}
                      type={i.type === "DATE" ? "date" : "text"}
                      value={inputs[i.name] ?? ""}
                      onChange={(e) => edit(() => setInputs((s) => ({ ...s, [i.name]: e.target.value })))}
                    />
                  </div>
                ),
              )}

              {tpl.confirmText && (
                <label className="flex items-start gap-2 rounded border border-warning-200 bg-warning-50 p-2 text-sm">
                  <input type="checkbox" className="mt-0.5" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
                  <span>
                    <span className="font-semibold">I confirm: </span>
                    {tpl.confirmText}
                  </span>
                </label>
              )}
            </>
          )}

          {preview && <PreviewBox preview={preview} />}
          {busy === "send" && count > 1 && (
            <p role="status" aria-live="polite" className="text-xs text-muted">
              {progress.done} / {progress.total} done
            </p>
          )}
          {error && <p className="text-xs text-error-700">{error}</p>}

          <DialogFooter>
            <button type="button" onClick={onClose} disabled={busy !== null} className="btn btn-sm btn-outline">
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void runPreview()}
              disabled={!formOk || busy !== null}
              className="btn btn-sm btn-outline disabled:opacity-50"
            >
              {busy === "preview" ? <Loader2 size={14} className="animate-spin" /> : null} Preview
              {count > 1 ? " first recipient" : ""}
            </button>
            <button
              type="button"
              onClick={() => void runSend()}
              disabled={!formOk || !preview || (needsConfirm && !confirmed) || busy !== null}
              className="btn btn-sm btn-navy disabled:opacity-50"
            >
              {busy === "send" ? <Loader2 size={14} className="animate-spin" /> : null} Send
            </button>
          </DialogFooter>
        </div>
      )}
    </Dialog>
  );
}

function PreviewBox({ preview }: { preview: MessagePreview }) {
  return (
    <div className="rounded border border-line bg-grey-50 p-2 text-xs" data-testid="message-preview">
      <p className="font-semibold text-ink">
        {preview.recipientName ?? `Customer #${preview.customerId}`} · {preview.maskedMobile ?? "no mobile"}
      </p>
      <ul className="mt-1 space-y-1">
        {preview.channels.map((c) => (
          <li key={c.channel}>
            <span className="font-semibold">{CHANNEL_LABEL[c.channel]}</span>{" "}
            <span className="font-mono text-muted">{c.providerTemplate}</span>
            {c.skipReason ? (
              <span className="text-warning-700"> — will be skipped: {c.skipReason}</span>
            ) : (
              <span className="text-muted"> — {c.params.join(" · ")}</span>
            )}
            {c.text && !c.skipReason && <span className="mt-1 block whitespace-pre-wrap text-ink">{c.text}</span>}
          </li>
        ))}
      </ul>
    </div>
  );
}

function ResultList({ rows }: { rows: Row[] }) {
  const sent = rows.filter((r) => r.result?.results.some((x) => x.status === "SENT")).length;
  return (
    <div>
      <p className="mb-2 text-sm text-ink">
        {sent} of {rows.length} recipient{rows.length === 1 ? "" : "s"} received at least one message.
      </p>
      <ul className="max-h-72 space-y-1 overflow-y-auto text-xs">
        {rows.map((r, i) => (
          <li key={i} className="rounded border border-line px-2 py-1">
            <span className="font-semibold text-ink">{r.label}</span>
            {r.error ? (
              <span className="block text-error-700">Failed: {r.error}</span>
            ) : (
              r.result?.results.map((x) => (
                <span
                  key={x.channel}
                  className={`block ${x.status === "SENT" ? "text-success-700" : x.status === "FAILED" ? "text-error-700" : "text-warning-700"}`}
                >
                  {CHANNEL_LABEL[x.channel]}: {x.status === "SENT" ? "sent" : x.status === "FAILED" ? "failed" : "skipped"}
                  {x.reason ? ` — ${x.reason}` : ""}
                </span>
              ))
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
