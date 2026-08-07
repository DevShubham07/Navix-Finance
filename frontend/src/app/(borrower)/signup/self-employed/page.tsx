"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { CalendarDays, IndianRupee, User } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice } from "@/lib/onboarding";
import { borrowerApi, rupeesToPaise } from "@/lib/api/applications";
import { clearBorrowerClientState } from "@/lib/api/live-journey";
import { formatApiError } from "@/lib/api/errors";

const today = () => new Date().toISOString().slice(0, 10);

/**
 * Self-employed branch. Submitting auto-rejects the application and starts a 90-day cooling-off
 * window (revamp.md decisions 20, 21). The borrower is shown a neutral message and is deliberately
 * never told which rule fired — the reason lives in the ADMIN rejection register.
 */
export default function SignupSelfEmployedPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const [fullName, setFullName] = React.useState("");
  const [dob, setDob] = React.useState("");
  const [income, setIncome] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [done, setDone] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const nameOk = fullName.trim().length > 2;
  const dobOk = !!dob && dob < today();
  const incomeNum = Number(income);
  const incomeOk = Number.isFinite(incomeNum) && incomeNum > 0;
  const formOk = nameOk && dobOk && incomeOk;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) {
      setTouched(true);
      return;
    }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      await saveProfileSlice(appId, {
        fullName: fullName.trim(),
        dob,
        annualSalaryPaise: rupeesToPaise(incomeNum),
      });
      await borrowerApi.selfEmployed(appId);
      clearBorrowerClientState();
      setDone(true);
    } catch (err) {
      setError(formatApiError(err, "Something went wrong — please try again."));
      setBusy(false);
    }
  };

  if (done) {
    return (
      <div className="form-card text-center">
        <h3 className="font-serif text-xl text-navy">You are not eligible at the moment</h3>
        <p className="mt-3 text-sm text-muted">
          Thank you for your interest in DhanBoost. We&apos;re unable to take your application forward
          right now. Please try again later.
        </p>
        <button type="button" onClick={() => router.replace("/")} className="btn btn-outline mt-6">
          Back to home
        </button>
      </div>
    );
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">A few more details and we&apos;ll review your application.</p>
        <Input
          label="Full name"
          required
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
          placeholder="Aarav Sharma"
          leftIcon={<User size={16} />}
          autoComplete="name"
          error={touched && !nameOk ? "Enter your full name" : undefined}
        />
        <Input
          label="Date of birth"
          required
          type="date"
          max={today()}
          value={dob}
          onChange={(e) => setDob(e.target.value)}
          leftIcon={<CalendarDays size={16} />}
          error={touched && !dobOk ? "Enter your date of birth" : undefined}
        />
        <Input
          label="Annual income"
          required
          inputMode="numeric"
          value={income}
          onChange={(e) => setIncome(e.target.value.replace(/[^\d]/g, ""))}
          placeholder="600000"
          leftIcon={<IndianRupee size={16} />}
          error={touched && !incomeOk ? "Enter your annual income" : undefined}
        />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/set-password" submit continueLabel="Submit" loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
