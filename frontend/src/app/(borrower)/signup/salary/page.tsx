"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { TrendingUp } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { usePersistedField } from "@/hooks/use-persisted-field";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { eligibleLimit } from "@/lib/calc/loan-math";
import { formatINR0 } from "@/lib/utils";

const DAY_OPTIONS = Array.from({ length: 31 }, (_, i) => ({ value: i + 1, label: `${i + 1}` }));

export default function SignupSalaryPage() {
  const router = useRouter();
  const { applicant, updateApplicant } = useBorrowerJourney();
  const [salary, setSalary] = usePersistedField<number>(applicant.monthlySalary);
  const [salaryDay, setSalaryDay] = usePersistedField<number>(applicant.salaryDay);
  const [touched, setTouched] = React.useState(false);

  const salaryOk = salary >= 10000;
  const limit = salaryOk ? eligibleLimit(salary) : 0;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!salaryOk) { setTouched(true); return; }
    updateApplicant({ monthlySalary: salary, salaryDay: salaryDay || 1 });
    router.push("/signup/email");
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Your net monthly salary sets your eligible limit (up to 25%) and the salary-day repayment date.
        </p>
        <Input
          label="Net monthly salary"
          required
          inputMode="numeric"
          value={salary ? String(salary) : ""}
          onChange={(e) => setSalary(Number(e.target.value.replace(/\D/g, "")) || 0)}
          placeholder="84000"
          leftIcon={<span className="font-serif text-muted">₹</span>}
          error={touched && !salaryOk ? "Enter your monthly salary (minimum ₹10,000)" : undefined}
          helperText="The amount credited to your salary account each month"
        />
        <Select
          label="Salary credit day"
          value={salaryDay || 1}
          onChange={(e) => setSalaryDay(Number(e.target.value))}
          options={DAY_OPTIONS}
          helperText="Day of the month your salary lands — your repayment date"
        />
      </div>

      {salaryOk && (
        <div className="mt-4 flex items-center gap-4 rounded border border-gold-soft bg-gold-50/60 p-5">
          <span className="grid h-12 w-12 flex-shrink-0 place-items-center rounded-full bg-gold-soft text-gold-dark">
            <TrendingUp size={22} />
          </span>
          <div>
            <div className="text-sm text-muted">Your eligible limit (25% of salary)</div>
            <div className="font-serif text-2xl font-bold text-navy">{formatINR0(limit)}</div>
          </div>
        </div>
      )}

      <WizardActions backHref="/signup/employment" submit continueLabel="Continue" />
      <Reassurance />
    </form>
  );
}
