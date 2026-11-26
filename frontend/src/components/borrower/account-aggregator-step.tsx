"use client";

import * as React from "react";
import { CheckCircle2, Landmark, Loader2, ShieldCheck } from "lucide-react";
import { BankStatementUpload, uploadBankStatements } from "@/components/borrower/bank-statement-upload";
import { verificationApi, type AaView } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

const POLL_MS = 4000;
const POLL_LIMIT = 45; // ~3 min, same budget as the DigiLocker screen

/**
 * Account Aggregator consent, shared by the intake (`/signup/account-aggregator`) and the offer
 * journey (`/loan/account-aggregator`). Same-tab redirect to Digitap's consent journey, which comes
 * back with `?returned=1`; our DB row is the truth, so we poll `aaStatus` rather than trust the URL.
 * After the max attempts the intake asks for bank statements instead; the offer simply lets them on.
 */
export function AccountAggregatorStep({
  mode,
  appId,
  onDone,
}: {
  mode: "intake" | "offer";
  appId: number | null;
  onDone: () => void;
}) {
  const [view, setView] = React.useState<AaView | null>(null);
  const [polling, setPolling] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [files, setFiles] = React.useState<File[]>([]);
  const [password, setPassword] = React.useState("");
  const done = React.useRef(onDone);
  done.current = onDone;

  React.useEffect(() => {
    if (appId == null) return;
    let live = true;
    const t: { id?: ReturnType<typeof setInterval> } = {};
    const returned = new URLSearchParams(window.location.search).get("returned") === "1";
    const stop = () => t.id && clearInterval(t.id);
    const load = (fn: () => Promise<AaView>) =>
      fn()
        .then((v) => {
          if (!live) return v;
          setView(v);
          return v;
        })
        .catch((err) => {
          if (live) setError(formatApiError(err, "Could not check your bank connection."));
          stop();
          setPolling(false);
          return null;
        });
    if (!returned) {
      void load(() => verificationApi.aaView(appId));
      return () => { live = false; };
    }
    setPolling(true);
    let n = 0;
    const tick = async () => {
      n += 1;
      const v = await load(() => verificationApi.aaStatus(appId));
      if (!v || !live) return;
      if (v.completed) {
        stop();
        setPolling(false);
        setTimeout(() => done.current(), 600);
      } else if (n >= POLL_LIMIT) {
        stop();
        setPolling(false);
      }
    };
    t.id = setInterval(() => void tick(), POLL_MS);
    void tick();
    return () => { live = false; stop(); };
  }, [appId]);

  const connect = async () => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      const base = `${window.location.origin}/${mode === "intake" ? "signup" : "loan"}/account-aggregator`;
      const { url } = await verificationApi.aaInit(appId, `${base}?returned=1`);
      window.location.assign(url);
    } catch (err) {
      setError(formatApiError(err, "Could not start the bank connection — please try again."));
      setBusy(false);
    }
  };

  const submitStatements = async () => {
    if (appId == null) return;
    if (files.length === 0) {
      setError("Upload at least one bank statement to continue.");
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      await uploadBankStatements(appId, files, password);
      await verificationApi.aaStatementFallback(appId);
      onDone();
    } catch (err) {
      setError(formatApiError(err, "Could not save your statements — please try again."));
      setBusy(false);
    }
  };

  const fallback = view?.fallbackAvailable && !view.completed && !polling;

  return (
    <div className="form-card">
      <div className="flex items-center gap-2 text-navy">
        <Landmark size={20} />
        <h3 className="font-serif text-lg">Connect your bank account</h3>
      </div>
      <p className="lead mt-2">
        Securely share your bank statement through the RBI-regulated Account Aggregator network. You approve it
        on your bank&apos;s consent screen, and you can withdraw it any time. We only read your statement; we
        can never move money.
      </p>

      {polling ? (
        <p className="mt-4 flex items-center gap-2 text-sm text-muted">
          <Loader2 size={16} className="animate-spin" /> Confirming your bank connection…
        </p>
      ) : view?.completed ? (
        <>
          <p className="mt-4 flex items-center gap-2 text-sm font-semibold text-success-700">
            <CheckCircle2 size={16} /> Bank connected
          </p>
          <button type="button" className="btn btn-primary mt-4" onClick={onDone}>
            Continue
          </button>
        </>
      ) : fallback ? (
        mode === "intake" ? (
          <>
            <p className="mt-4 text-sm text-ink">
              We could not complete the bank connection. Please upload your bank statements instead.
            </p>
            <BankStatementUpload
              files={files}
              onFiles={setFiles}
              password={password}
              onPassword={setPassword}
              onError={setError}
            />
            <button type="button" className="btn btn-primary mt-4" disabled={busy} onClick={() => void submitStatements()}>
              {busy ? "Saving…" : "Continue with statements"}
            </button>
          </>
        ) : (
          <>
            <p className="mt-4 text-sm text-ink">We could not complete the bank connection. You can continue without it.</p>
            <button type="button" className="btn btn-primary mt-4" onClick={onDone}>
              Continue without Account Aggregator
            </button>
          </>
        )
      ) : (
        <>
          <button type="button" className="btn btn-primary mt-4" disabled={busy || appId == null || !view} onClick={() => void connect()}>
            {busy ? "Opening…" : "Connect bank account"}
          </button>
          {view ? (
            <p className="mt-2 flex items-center gap-1.5 text-xs text-muted">
              <ShieldCheck size={14} className="text-success-600" /> {view.attemptsLeft} attempt
              {view.attemptsLeft === 1 ? "" : "s"} left
            </p>
          ) : null}
        </>
      )}

      {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
    </div>
  );
}
