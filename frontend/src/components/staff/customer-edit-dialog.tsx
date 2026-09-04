"use client";

import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { Input } from "@/components/ui";
import { Dialog, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { errMessage } from "@/components/staff/live-pipeline";
import { customersApi, rupeesToPaise } from "@/lib/api/applications";

/**
 * ADMIN correcting a customer's details without leaving the Customers page — chiefly the name and
 * date of birth, which are the two bureau prerequisites that park a file with nothing a reviewer
 * can act on.
 *
 * **This dialog MUST send every field, changed or not.** `PUT /api/customers/{id}/profile` is a
 * full replace, not a patch: it writes all of name, address, employer, employment status, salary
 * bank and the four salary figures unconditionally, and `logIfChanged` faithfully audits each
 * erasure. A dialog that posted only `{ fullName }` would silently wipe eight other fields and leave
 * a tidy audit trail proving it. So it opens on the fetched profile and echoes the whole thing back,
 * and shows a loading state rather than an empty form while that fetch is in flight — an empty form
 * submitted early is the same data loss by another route.
 *
 * Date of birth is the one exception on the backend: it is patch-semantics there (null means leave
 * it alone) so that the customer detail page's older edit card, which does not send one, cannot
 * erase it.
 *
 * Identity stays locked. PAN is not editable anywhere, and a mobile change keeps its two-step OTP
 * flow on the customer detail page.
 */
export function CustomerEditDialog({
  customerId,
  onClose,
}: {
  customerId: number;
  onClose: () => void;
}) {
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["customer-detail", customerId],
    queryFn: () => customersApi.get(customerId),
  });
  const profile = q.data?.profile ?? null;

  const [form, setForm] = React.useState<Record<string, string> | null>(null);

  // Hydrate once, from the fetched profile. Until this runs the form is null and the dialog renders
  // a skeleton — never an editable blank form, which would submit nine empty fields.
  React.useEffect(() => {
    if (!profile || form !== null) return;
    setForm({
      fullName: profile.fullName ?? "",
      dob: profile.dob ?? "",
      employer: profile.employer ?? "",
      employmentStatus: profile.employmentStatus ?? "",
      address: profile.address ?? "",
      salaryBank: profile.salaryBank ?? "",
      monthlySalary:
        profile.monthlySalaryPaise != null ? String(Math.round(profile.monthlySalaryPaise / 100)) : "",
      annualSalary:
        profile.annualSalaryPaise != null ? String(Math.round(profile.annualSalaryPaise / 100)) : "",
      salaryPercentage: profile.salaryPercentage != null ? String(profile.salaryPercentage) : "",
      incrementPercentage:
        profile.incrementPercentage != null ? String(profile.incrementPercentage) : "",
    });
  }, [profile, form]);

  const set = (key: string) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => (f ? { ...f, [key]: e.target.value } : f));

  const save = useMutation({
    mutationFn: () => {
      if (!form) throw new Error("Profile has not loaded yet.");
      return customersApi.updateProfile(customerId, {
        fullName: form.fullName.trim() || null,
        // Omitted rather than nulled when blank: clearing a date of birth is not a supported
        // correction, and the backend treats null here as "leave it alone".
        ...(form.dob.trim() ? { dob: form.dob.trim() } : {}),
        employer: form.employer.trim() || null,
        employmentStatus: form.employmentStatus.trim() || null,
        address: form.address.trim() || null,
        salaryBank: form.salaryBank.trim() || null,
        monthlySalaryPaise: form.monthlySalary
          ? rupeesToPaise(Number(form.monthlySalary.replace(/[^\d.]/g, "")))
          : null,
        annualSalaryPaise: form.annualSalary
          ? rupeesToPaise(Number(form.annualSalary.replace(/[^\d.]/g, "")))
          : null,
        salaryPercentage: form.salaryPercentage ? Number(form.salaryPercentage) : null,
        incrementPercentage: form.incrementPercentage ? Number(form.incrementPercentage) : null,
      });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["customers"] });
      qc.invalidateQueries({ queryKey: ["customer", customerId] });
      qc.invalidateQueries({ queryKey: ["customer-detail", customerId] });
      qc.invalidateQueries({ queryKey: ["customer-failure", customerId] });
      onClose();
    },
  });

  return (
    <Dialog open onClose={onClose} aria-label="Edit customer details">
      <DialogHeader>
        <DialogTitle>Edit customer details</DialogTitle>
      </DialogHeader>

      {q.isLoading || !form ? (
        <div className="h-48 animate-pulse rounded bg-grey-100" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <>
          <p className="mb-3 text-xs text-muted">
            PAN and Aadhaar are locked. Mobile is corrected from the customer page, with OTP
            verification. Salary edits are audited and recompute the eligible limit.
          </p>
          <Input label="Full name" value={form.fullName} onChange={set("fullName")} className="!mb-2" />
          <Input
            label="Date of birth"
            type="date"
            value={form.dob}
            onChange={set("dob")}
            className="!mb-2"
            helperText="Required by the bureau before a credit check can run."
          />
          <Input label="Employer" value={form.employer} onChange={set("employer")} className="!mb-2" />
          <Input
            label="Employment status"
            value={form.employmentStatus}
            onChange={set("employmentStatus")}
            className="!mb-2"
          />
          <Input
            label="Monthly salary (₹)"
            inputMode="numeric"
            value={form.monthlySalary}
            onChange={(e) =>
              setForm((f) => (f ? { ...f, monthlySalary: e.target.value.replace(/[^\d]/g, "") } : f))
            }
            className="!mb-2"
          />
          <Input
            label="Annual salary (₹)"
            inputMode="numeric"
            value={form.annualSalary}
            onChange={(e) =>
              setForm((f) => (f ? { ...f, annualSalary: e.target.value.replace(/[^\d]/g, "") } : f))
            }
            className="!mb-2"
          />
          <Input
            label="Salary percentage (%)"
            inputMode="decimal"
            value={form.salaryPercentage}
            onChange={(e) =>
              setForm((f) =>
                f ? { ...f, salaryPercentage: e.target.value.replace(/[^\d.]/g, "") } : f,
              )
            }
            className="!mb-2"
          />
          <Input
            label="Increment percentage (%)"
            inputMode="decimal"
            value={form.incrementPercentage}
            onChange={(e) =>
              setForm((f) =>
                f ? { ...f, incrementPercentage: e.target.value.replace(/[^\d.]/g, "") } : f,
              )
            }
            className="!mb-2"
          />
          <Input label="Salary bank" value={form.salaryBank} onChange={set("salaryBank")} className="!mb-2" />
          <Input label="Address" value={form.address} onChange={set("address")} className="!mb-2" />
          {save.error && <p className="mt-2 text-sm text-error-700">{errMessage(save.error)}</p>}
        </>
      )}

      <DialogFooter>
        <button onClick={onClose} className="btn btn-sm btn-outline">
          Cancel
        </button>
        <button
          onClick={() => save.mutate()}
          disabled={save.isPending || !form}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          {save.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Save changes
        </button>
      </DialogFooter>
    </Dialog>
  );
}
