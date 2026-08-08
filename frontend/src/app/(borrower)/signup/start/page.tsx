"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { CreditCard, Phone } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { TermsModal } from "@/components/borrower/terms-modal";
import { useOnboarding, TERMS_VERSION } from "@/lib/onboarding";
import {
  createOrResumeDraft,
  fetchBorrowerSession,
  writeStoredAppId,
  routeForBlockedStart,
} from "@/lib/api/live-journey";
import { formatApiError } from "@/lib/api/errors";
import { ApplicationApiError, journeyApi } from "@/lib/api/applications";
import { normalizeMobile } from "@/lib/utils";

const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;

export default function SignupStartPage() {
  const router = useRouter();
  const { mounted, draft } = useOnboarding();
  const [pan, setPan] = React.useState("");
  const [mobile, setMobile] = React.useState("");
  const [terms, setTerms] = React.useState(false);
  const [pep, setPep] = React.useState(false);
  const [showTerms, setShowTerms] = React.useState(false);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!mounted) return;
    setPan(draft.pan);
    setMobile(draft.mobile);
    setTerms(draft.termsVersion === TERMS_VERSION);
    setPep(draft.pepDeclared);
  }, [mounted]); // eslint-disable-line react-hooks/exhaustive-deps

  // Already signed in (a returning borrower, or the same borrower on a second device)? Send them
  // to the step the SERVER says they reached.
  //
  // This used to hard-code /signup/employment and rely on the wizard layout's journey guard to
  // correct it. That guard reads the app id from localStorage once on mount and fires at most
  // once, so on a fresh device — exactly the case this branch exists for — it had nothing to work
  // with and never ran. The borrower landed on step 3 regardless of how far they had actually got,
  // and had to re-walk screens they had already completed (revamp.md C1).
  React.useEffect(() => {
    let live = true;
    fetchBorrowerSession()
      .then(async (session) => {
        if (!session || !live) return;
        const app = await createOrResumeDraft(session);
        writeStoredAppId(app.id);
        const route = await journeyApi
          .get(app.id)
          .then((j) => j.route)
          .catch(() => null);
        if (!live) return;
        router.replace(route && route.startsWith("/signup/") ? route : "/signup/set-password");
      })
      .catch((e) => {
        const code = e instanceof ApplicationApiError ? e.code : undefined;
        const to = code ? routeForBlockedStart(code) : null;
        if (to && live) router.replace(to);
      });
    return () => {
      live = false;
    };
  }, [router]);

  const panOk = PAN_RE.test(pan);
  const mobileOk = /^[6-9]\d{9}$/.test(mobile);
  const formOk = panOk && mobileOk && terms && pep;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) {
      setTouched(true);
      return;
    }
    setBusy(true);
    setError(undefined);
    // The consents ride to the next screen, which creates the application and persists them —
    // there is nothing to write them to until the OTP succeeds.
    draft.patch({ pan, mobile, termsVersion: TERMS_VERSION, pepDeclared: true });
    try {
      router.push("/signup/otp");
    } catch (err) {
      setError(formatApiError(err, "Something went wrong — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Let&apos;s start with your PAN and mobile number. It takes about two minutes.
        </p>

        <Input
          label="PAN"
          required
          value={pan}
          onChange={(e) => setPan(e.target.value.toUpperCase().slice(0, 10))}
          placeholder="ABCDE1234F"
          leftIcon={<CreditCard size={16} />}
          autoCapitalize="characters"
          error={touched && !panOk ? "Enter a valid 10-character PAN" : undefined}
        />
        <Input
          label="Mobile number"
          required
          inputMode="numeric"
          value={mobile}
          onChange={(e) => setMobile(normalizeMobile(e.target.value))}
          placeholder="9876543210"
          leftIcon={<Phone size={16} />}
          autoComplete="tel"
          helperText="We'll send a verification code to this number."
          error={touched && !mobileOk ? "Enter a valid 10-digit mobile number" : undefined}
        />

        <label className="checkbox">
          {/* Ticking never sets the flag directly — only Agree in the modal does. `checked` stays
              controlled by `terms`, so React re-renders the box back to unticked on cancel. */}
          <input
            type="checkbox"
            checked={terms}
            onChange={(e) => (e.target.checked ? setShowTerms(true) : setTerms(false))}
          />
          <span>
            I agree to the{" "}
            <button type="button" className="arrow-link" onClick={() => setShowTerms(true)}>
              Terms &amp; Conditions
            </button>
          </span>
        </label>
        <label className="checkbox">
          <input type="checkbox" checked={pep} onChange={(e) => setPep(e.target.checked)} />
          <span>I confirm that I am not a Politically Exposed Person, nor related to one.</span>
        </label>
        {touched && (!terms || !pep) ? (
          <p className="mt-2 text-sm text-error-600">Both confirmations are required to continue.</p>
        ) : null}

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      {/* Both confirmations gate the button itself, not just the submit handler (revamp.md
          decision 19: "Continue stays disabled until ticked"). Validating only on click let the
          button look available while the mandatory PEP declaration was still unticked — the
          borrower learned it was refused only after trying. */}
      <WizardActions submit loading={busy} disabled={busy || !terms || !pep} />
      <Reassurance />

      <TermsModal
        open={showTerms}
        onDecline={() => {
          setTerms(false);
          setShowTerms(false);
        }}
        onAgree={() => {
          setTerms(true);
          setShowTerms(false);
        }}
      />
    </form>
  );
}
