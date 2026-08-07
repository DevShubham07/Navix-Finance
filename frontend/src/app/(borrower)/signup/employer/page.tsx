"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Building2, CalendarDays, IndianRupee } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { useOnboarding, saveProfileSlice, completeStep, useSavedProfile } from "@/lib/onboarding";
import { rupeesToPaise } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

const today = () => new Date().toISOString().slice(0, 10);

export default function SignupEmployerPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const saved = useSavedProfile(appId);
  const [employer, setEmployer] = React.useState("");
  const [salaryDate, setSalaryDate] = React.useState("");
  const [salary, setSalary] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (!saved) return;
    if (saved.employer) setEmployer(saved.employer);
    if (saved.previousSalaryDate) setSalaryDate(saved.previousSalaryDate);
    if (saved.monthlySalaryPaise) setSalary(String(Math.round(saved.monthlySalaryPaise / 100)));
  }, [saved]);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  const employerOk = employer.trim().length > 1;
  const dateOk = !!salaryDate && salaryDate <= today();
  const salaryNum = Number(salary);
  const salaryOk = Number.isFinite(salaryNum) && salaryNum > 0;
  const formOk = employerOk && dateOk && salaryOk;

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
        employer: employer.trim(),
        previousSalaryDate: salaryDate,
        monthlySalaryPaise: rupeesToPaise(salaryNum),
      });
      await completeStep(appId, "EMPLOYER", router, "/signup/email");
    } catch (err) {
      setError(formatApiError(err, "Could not save — please try again."));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">Tell us where you work and when you get paid.</p>
        <Input
          label="Company name"
          required
          value={employer}
          onChange={(e) => setEmployer(e.target.value)}
          placeholder="Infosys Limited"
          leftIcon={<Building2 size={16} />}
          autoComplete="organization"
          error={touched && !employerOk ? "Enter your employer's name" : undefined}
        />
        <Input
          label="Previous salary date"
          required
          type="date"
          max={today()}
          value={salaryDate}
          onChange={(e) => setSalaryDate(e.target.value)}
          leftIcon={<CalendarDays size={16} />}
          helperText="The date you were last paid. We use it to set your repayment date."
          error={touched && !dateOk ? "Pick the date you were last paid" : undefined}
        />
        <Input
          label="Monthly salary (in-hand)"
          required
          inputMode="numeric"
          value={salary}
          onChange={(e) => setSalary(e.target.value.replace(/[^\d]/g, ""))}
          placeholder="45000"
          leftIcon={<IndianRupee size={16} />}
          helperText="The amount credited to your bank each month."
          error={touched && !salaryOk ? "Enter your monthly in-hand salary" : undefined}
        />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref="/signup/set-password" submit loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
