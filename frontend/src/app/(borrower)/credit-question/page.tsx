"use client";

import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { CheckCircle2, Loader2, ShieldQuestion } from "lucide-react";
import { verificationApi, type StepResult } from "@/lib/api/applications";
import { readStoredAppId } from "@/lib/api/live-journey";
import { formatApiError } from "@/lib/api/errors";

/**
 * The bureau's knowledge-based-authentication (KBA) question.
 *
 * ONE page, TWO entry points: the signup consent step routes here when its bureau pull came back with
 * a question (freshest possible order — minted seconds earlier), and the emailed nudge for borrowers
 * already parked links straight here.
 *
 * Deliberately NOT under /signup/*: that layout runs `useJourneyGuard`, which would redirect straight
 * off this page, and an unregistered segment there renders as "Step 1 of 10 — Your details".
 *
 * Three rules this file must keep:
 *  1. Mounting NEVER mints a question. Every mint is a live, billable Fintrix call with no sandbox,
 *     so it only ever happens on a deliberate click (guarded again by a ref latch below, and by a
 *     60s cooldown server-side).
 *  2. Option strings go back VERBATIM. CRIF pads them (" KOTAK MAHINDRA BANK ") and compares
 *     literally — a trimmed answer is a wrong answer, and a wrong answer costs a re-mint.
 *  3. Nothing here blocks. "I don't recognise any of these" is always available, matching the
 *     "never stop the borrower at this step" policy the bureau step has always had.
 */

type Phase = "loading" | "question" | "needsMint" | "done" | "skipped" | "none";

/** The challenge fields `bureauChallengeReview` writes into the BUREAU row's `derived`. */
type Challenge = {
  question: string;
  options: string[];
  hasReportId: boolean;
  skipped: boolean;
};

/**
 * The options arrive in one of TWO shapes and both must work. Straight off the answer/refresh call
 * it is a real array; read back through the stored row it is a JSON *string*, because the backend
 * serialises non-scalar `derived` values with toString(). Treating the string case as "no options"
 * would send the borrower down the re-mint path — a needless billable provider call.
 */
function readOptions(raw: unknown): string[] {
  if (Array.isArray(raw)) return raw.filter((o): o is string => typeof o === "string");
  if (typeof raw === "string" && raw.trim().startsWith("[")) {
    try {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed.filter((o): o is string => typeof o === "string");
    } catch {
      /* fall through to none */
    }
  }
  return [];
}

function readChallenge(row: StepResult | undefined): Challenge | null {
  const d = row?.derived as Record<string, unknown> | undefined;
  if (!d || d.bureauChallenge !== true) return null;
  const options = readOptions(d.bureauChallengeOptions);
  return {
    question: typeof d.bureauChallengeQuestion === "string" ? d.bureauChallengeQuestion : "",
    options,
    // Rows parked before the answer flow shipped hold an order id and nothing else. They cannot be
    // answered at all, so those borrowers are offered a fresh question instead.
    hasReportId: typeof d.bureauChallengeReportId === "string" && d.bureauChallengeReportId.length > 0,
    skipped: d.bureauChallengeSkipped === true,
  };
}

// useSearchParams() forces a client-side bailout, which fails the prerender unless it sits under
// a Suspense boundary. Same inner-component split as reset-password/page.tsx and staff/activate.
function CreditQuestionInner() {
  const searchParams = useSearchParams();
  const [appId, setAppId] = React.useState<number | null>(null);
  const [phase, setPhase] = React.useState<Phase>("loading");
  const [challenge, setChallenge] = React.useState<Challenge | null>(null);
  const [choice, setChoice] = React.useState<string | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  const [notice, setNotice] = React.useState<string>();
  /** Belt-and-braces against a double-click or a re-render firing a billable mint twice. */
  const inFlight = React.useRef(false);

  React.useEffect(() => {
    const fromQuery = Number(searchParams.get("appId"));
    setAppId(Number.isFinite(fromQuery) && fromQuery > 0 ? fromQuery : readStoredAppId());
  }, [searchParams]);

  /** Reads the stored row only — never calls the provider. */
  const load = React.useCallback(async (id: number) => {
    try {
      const rows = await verificationApi.summary(id);
      apply(rows.find((r) => r.checkType === "BUREAU"));
    } catch (err) {
      setError(formatApiError(err, "We couldn't load your question — please refresh."));
      setPhase("none");
    }
  }, []);

  const apply = (row: StepResult | undefined) => {
    const next = readChallenge(row);
    setChallenge(next);
    setChoice(null);
    if (!next) {
      setPhase(row?.status === "PASS" ? "done" : "none");
    } else if (next.skipped) {
      setPhase("skipped");
    } else {
      setPhase(next.hasReportId && next.options.length > 0 ? "question" : "needsMint");
    }
  };

  React.useEffect(() => {
    if (appId != null) void load(appId);
  }, [appId, load]);

  const run = async (fn: () => Promise<StepResult>, fallback: string) => {
    if (appId == null || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(undefined);
    setNotice(undefined);
    try {
      apply(await fn());
    } catch (err) {
      setError(formatApiError(err, fallback));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };

  const mint = () =>
    run(
      () => verificationApi.bureauChallengeRefresh(appId as number),
      "We couldn't fetch a question just now — please try again in a minute.",
    );

  const submit = async () => {
    if (!choice) return;
    await run(async () => {
      // `choice` is the option string exactly as the bureau sent it. Do not trim.
      const result = await verificationApi.bureauChallengeAnswer(appId as number, choice);
      if (result.status === "REVIEW" && result.message) setNotice(result.message);
      return result;
    }, "That didn't go through — please try again.");
  };

  const skip = () =>
    run(() => verificationApi.bureauChallengeSkip(appId as number), "Please try again.");

  if (phase === "loading") {
    return (
      <div className="container max-w-content py-16 text-center">
        <Loader2 size={28} className="mx-auto animate-spin text-navy" />
      </div>
    );
  }

  return (
    <div className="container max-w-content py-10">
      <div className="form-card">
        {phase === "done" ? (
          <Outcome
            icon={<CheckCircle2 size={28} className="mx-auto text-success-600" />}
            title="All done — thank you"
            body="That's the last thing we needed. Our team is reviewing your application now."
          />
        ) : phase === "skipped" ? (
          <Outcome
            title="Thanks — we'll take it from here"
            body="No problem. Our credit team will complete this check manually; you don't need to do anything else."
          />
        ) : phase === "none" ? (
          <Outcome
            title="Nothing to answer"
            body="There's no security question waiting on your application right now."
          />
        ) : (
          <>
            <ShieldQuestion size={26} className="text-navy" />
            <h1 className="mt-3 font-serif text-xl text-navy">One quick security question</h1>
            <p className="mt-1 text-sm text-muted">
              The credit bureau needs to confirm it&apos;s really you before releasing your report.
              Only you know the answer.
            </p>

            {phase === "needsMint" ? (
              <div className="mt-6">
                <p className="text-sm text-muted">
                  Your question has expired. Get a fresh one — it only takes a moment.
                </p>
                <button type="button" className="btn btn-primary mt-4" onClick={mint} disabled={busy}>
                  {busy ? "Getting your question…" : "Get my question"}
                </button>
              </div>
            ) : (
              <div className="mt-6">
                <p className="font-medium text-ink">{challenge?.question}</p>
                <div className="mt-4 space-y-2">
                  {challenge?.options.map((option, i) => (
                    <label
                      key={`${option}-${i}`}
                      className="flex cursor-pointer items-center gap-3 rounded-lg border border-line p-3 hover:border-navy"
                    >
                      <input
                        type="radio"
                        name="kba"
                        // value is the raw, space-padded option — never trimmed, see the file header.
                        checked={choice === option}
                        onChange={() => setChoice(option)}
                        disabled={busy}
                      />
                      <span className="text-sm text-ink">{option.trim() || "None of these"}</span>
                    </label>
                  ))}
                </div>
                <button
                  type="button"
                  className="btn btn-primary mt-5 w-full"
                  onClick={submit}
                  disabled={busy || !choice}
                >
                  {busy ? "Checking…" : "Confirm answer"}
                </button>
                <button
                  type="button"
                  className="btn btn-outline mt-2 w-full"
                  onClick={skip}
                  disabled={busy}
                >
                  I don&apos;t recognise any of these
                </button>
              </div>
            )}

            {notice ? <p className="mt-3 text-sm text-warning-700">{notice}</p> : null}
            {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
          </>
        )}
      </div>
    </div>
  );
}

export default function CreditQuestionPage() {
  return (
    <Suspense
      fallback={
        <div className="container max-w-content py-16 text-center">
          <Loader2 size={28} className="mx-auto animate-spin text-navy" />
        </div>
      }
    >
      <CreditQuestionInner />
    </Suspense>
  );
}

function Outcome({ icon, title, body }: { icon?: React.ReactNode; title: string; body: string }) {
  return (
    <div className="text-center">
      {icon}
      <h1 className="mt-3 font-serif text-xl text-navy">{title}</h1>
      <p className="mt-2 text-sm text-muted">{body}</p>
      <Link href="/loan/status" className="btn btn-primary mt-6">
        Continue
      </Link>
    </div>
  );
}
