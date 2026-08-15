"use client";

import * as React from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Loader2, RefreshCw, Pencil, Ban, Trash2, AlertTriangle, Gauge } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { Tabs } from "@/components/ui/tabs";
import { PageHeader } from "@/components/staff/staff-ui";
import { PermissionGate, NoAccessNotice, errMessage } from "@/components/staff/live-pipeline";
import { CUSTOMER_TABS, CustomerTabBody } from "@/components/staff/customer-tabs";
import { CreditScoreGauge } from "@/components/staff/credit-score-gauge";
import {
  customersApi,
  adminApi,
  rupeesToPaise,
  type CustomerDetail,
  type BlocklistType,
} from "@/lib/api/applications";

/** Loan statuses that mean the loan is still live (vs. a past/closed loan). */
const OPEN_LOAN = new Set(["ACTIVE", "OVERDUE", "IN_COLLECTIONS", "DISBURSED", "DEFAULTED"]);

export default function CustomerDetailPage() {
  const { customerId } = useParams<{ customerId: string }>();
  const id = Number(customerId);
  const qc = useQueryClient();
  const [tab, setTab] = React.useState("personal");
  const q = useQuery({ queryKey: ["customer", id], queryFn: () => customersApi.get(id), enabled: Number.isFinite(id) });
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["customer", id] });
    qc.invalidateQueries({ queryKey: ["customer-detail", id] });
    qc.invalidateQueries({ queryKey: ["customers"] });
  };

  const c = q.data;
  const currentLoan = c?.loans.find((l) => OPEN_LOAN.has(l.status)) ?? null;

  return (
    <div>
      <Link href="/staff/customers" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-navy">
        <ArrowLeft size={15} /> All customers
      </Link>
      <PageHeader
        title={c?.profile?.fullName ?? `Customer #${id}`}
        subtitle={`Customer #${id} · borrower history${c?.ownerName ? ` · owner ${c.ownerName}` : " · Unallocated"}`}
      >
        <button onClick={() => q.refetch()} className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink">
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <PermissionGate permission="customer:view" fallback={<NoAccessNotice />}>
        {q.isLoading ? (
          <div className="h-48 animate-pulse rounded border border-line bg-white" />
        ) : q.error || !c ? (
          <p className="text-sm text-error-700">{q.error ? errMessage(q.error) : "Customer not found."}</p>
        ) : (
          <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,340px)]">
            <div className="min-w-0 rounded border border-line bg-white p-4 shadow-sm">
              <Tabs tabs={CUSTOMER_TABS} active={tab} onChange={setTab} />
              <div className="mt-3 max-h-[68vh] overflow-y-auto text-[10.4px]">
                <CustomerTabBody tab={tab} detail={c} customerId={id} onChanged={invalidate} />
              </div>
            </div>
            <div className="space-y-6">
              {/* At-a-glance dial so the score is visible without opening the Credit Report tab. */}
              <Card title="Credit score" icon={<Gauge size={16} />}>
                <CreditScoreGauge
                  score={c.profile?.creditScore ?? c.creditBrief?.creditScore ?? null}
                  starRating={c.profile?.starRating ?? c.creditBrief?.starRating ?? null}
                  recommendation={c.profile?.recommendation ?? c.creditBrief?.recommendation ?? null}
                  state={c.creditBrief?.bureauState ?? "NOT_FETCHED"}
                  size="sm"
                />
              </Card>
              <PermissionGate permission="customer:manage">
                <AdminEditCard detail={c} onSaved={invalidate} />
                <BlocklistCard customerId={id} />
                <DeleteCustomerCard
                  customerId={id}
                  name={c.profile?.fullName ?? `Customer #${id}`}
                  hasLiveLoan={!!currentLoan}
                />
              </PermissionGate>
            </div>
          </div>
        )}
      </PermissionGate>
    </div>
  );
}

function Card({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">{icon} {title}</div>
      {children}
    </div>
  );
}

function DeleteCustomerCard({ customerId, name, hasLiveLoan }: { customerId: number; name: string; hasLiveLoan: boolean }) {
  const router = useRouter();
  const [arming, setArming] = React.useState(false);
  const [typed, setTyped] = React.useState("");
  const m = useMutation({
    mutationFn: () => customersApi.remove(customerId),
    onSuccess: () => router.push("/staff/customers"),
  });
  const confirmed = typed.trim() === name.trim();

  return (
    <div className="rounded border border-error-200 bg-error-50 p-5 shadow-sm">
      <div className="mb-2 flex items-center gap-2 font-serif text-base font-semibold text-error-700">
        <AlertTriangle size={16} /> Danger zone
      </div>
      <p className="mb-3 text-xs text-error-700">
        Permanently delete this customer and <strong>all</strong> of their data — applications, loans,
        payments, verifications, documents and collections. This cannot be undone.
      </p>
      {hasLiveLoan ? (
        <p className="mb-3 rounded border border-error-200 bg-white/60 px-2 py-1 text-xs font-semibold text-error-700">
          ⚠ This customer has a live loan — deleting also erases that active loan record.
        </p>
      ) : null}

      {!arming ? (
        <button
          onClick={() => { setArming(true); setTyped(""); m.reset(); }}
          className="flex items-center gap-1.5 rounded border border-error-300 bg-white px-3 py-1.5 text-xs font-semibold text-error-700 hover:bg-error-100"
        >
          <Trash2 size={13} /> Delete this user
        </button>
      ) : (
        <div className="space-y-2">
          <label className="block text-xs text-error-700">
            Type <span className="font-mono font-semibold">{name}</span> to confirm:
          </label>
          <Input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={name} autoFocus />
          <div className="flex items-center gap-2">
            <button
              onClick={() => m.mutate()}
              disabled={!confirmed || m.isPending}
              className="flex items-center gap-1.5 rounded bg-error-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-error-700 disabled:opacity-50"
            >
              {m.isPending ? <Loader2 size={13} className="animate-spin" /> : <Trash2 size={13} />}
              Permanently delete
            </button>
            <button
              onClick={() => { setArming(false); setTyped(""); }}
              className="rounded border border-line bg-white px-3 py-1.5 text-xs text-muted hover:bg-grey-100"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
      {m.error ? <p className="mt-2 text-xs text-error-700">{errMessage(m.error)}</p> : null}
    </div>
  );
}

function AdminEditCard({ detail, onSaved }: { detail: CustomerDetail; onSaved: () => void }) {
  const p = detail.profile;
  const [fullName, setFullName] = React.useState(p?.fullName ?? "");
  const [employer, setEmployer] = React.useState(p?.employer ?? "");
  const [employmentStatus, setEmploymentStatus] = React.useState(p?.employmentStatus ?? "");
  const [salary, setSalary] = React.useState(p?.monthlySalaryPaise != null ? String(Math.round(p.monthlySalaryPaise / 100)) : "");
  const [annualSalary, setAnnualSalary] = React.useState(p?.annualSalaryPaise != null ? String(Math.round(p.annualSalaryPaise / 100)) : "");
  const [salaryPct, setSalaryPct] = React.useState(p?.salaryPercentage != null ? String(p.salaryPercentage) : "");
  const [incrementPct, setIncrementPct] = React.useState(p?.incrementPercentage != null ? String(p.incrementPercentage) : "");
  const [salaryBank, setSalaryBank] = React.useState(p?.salaryBank ?? "");
  const [address, setAddress] = React.useState(p?.address ?? "");

  const m = useMutation({
    mutationFn: () =>
      customersApi.updateProfile(detail.customerId, {
        fullName: fullName.trim() || null,
        employer: employer.trim() || null,
        employmentStatus: employmentStatus.trim() || null,
        monthlySalaryPaise: salary ? rupeesToPaise(Number(salary.replace(/[^\d.]/g, ""))) : null,
        annualSalaryPaise: annualSalary ? rupeesToPaise(Number(annualSalary.replace(/[^\d.]/g, ""))) : null,
        salaryPercentage: salaryPct ? Number(salaryPct) : null,
        incrementPercentage: incrementPct ? Number(incrementPct) : null,
        salaryBank: salaryBank.trim() || null,
        address: address.trim() || null,
      }),
    onSuccess: onSaved,
  });

  return (
    <Card title="Edit KYC / salary (admin)" icon={<Pencil size={16} />}>
      <p className="mb-3 text-xs text-muted">Identity (PAN/Aadhaar/mobile) is locked. Salary edits are audited and recompute the eligible limit.</p>
      <Input label="Full name" value={fullName} onChange={(e) => setFullName(e.target.value)} className="!mb-2" />
      <Input label="Employer" value={employer} onChange={(e) => setEmployer(e.target.value)} className="!mb-2" />
      <Input label="Employment status" value={employmentStatus} onChange={(e) => setEmploymentStatus(e.target.value)} className="!mb-2" />
      <Input label="Monthly salary (₹)" inputMode="numeric" value={salary} onChange={(e) => setSalary(e.target.value.replace(/[^\d]/g, ""))} className="!mb-2" />
      <Input label="Annual salary (₹)" inputMode="numeric" value={annualSalary} onChange={(e) => setAnnualSalary(e.target.value.replace(/[^\d]/g, ""))} className="!mb-2" />
      <Input label="Salary percentage (%)" inputMode="decimal" value={salaryPct} onChange={(e) => setSalaryPct(e.target.value.replace(/[^\d.]/g, ""))} className="!mb-2" />
      <Input label="Increment percentage (%)" inputMode="decimal" value={incrementPct} onChange={(e) => setIncrementPct(e.target.value.replace(/[^\d.]/g, ""))} className="!mb-2" />
      <Input label="Salary bank" value={salaryBank} onChange={(e) => setSalaryBank(e.target.value)} className="!mb-2" />
      <Input label="Address" value={address} onChange={(e) => setAddress(e.target.value)} className="!mb-3" />
      {m.error && <p className="mb-2 text-sm text-error-700">{errMessage(m.error)}</p>}
      {m.isSuccess && <p className="mb-2 text-sm text-success-700">Saved.</p>}
      <button onClick={() => m.mutate()} disabled={m.isPending} className="btn btn-sm btn-navy btn-block disabled:opacity-50">
        {m.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Save changes
      </button>
    </Card>
  );
}

const BLOCKLIST_TYPES: BlocklistType[] = ["PAN", "AADHAAR_REF", "PHONE", "DEVICE", "BANK_ACCOUNT"];

function BlocklistCard({ customerId }: { customerId: number }) {
  const [type, setType] = React.useState<BlocklistType>("PAN");
  const [value, setValue] = React.useState("");
  const [reason, setReason] = React.useState("");
  const m = useMutation({
    mutationFn: () => adminApi.addBlocklist({ type, value: value.trim(), reason: reason.trim() || `Flagged from customer #${customerId}` }),
    onSuccess: () => { setValue(""); setReason(""); },
  });
  return (
    <Card title="Add to blocklist (admin)" icon={<Ban size={16} />}>
      <p className="mb-3 text-xs text-muted">Flag a PAN / phone / device against fraud. Enter the full value to block.</p>
      <Select label="Type" value={type} onChange={(e) => setType(e.target.value as BlocklistType)} options={BLOCKLIST_TYPES.map((t) => ({ value: t, label: t }))} className="!mb-2" />
      <Input label="Value" value={value} onChange={(e) => setValue(e.target.value)} placeholder="e.g. ABCDE1234F" className="!mb-2" />
      <Input label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="optional" className="!mb-3" />
      {m.error && <p className="mb-2 text-sm text-error-700">{errMessage(m.error)}</p>}
      {m.isSuccess && <p className="mb-2 text-sm text-success-700">Added to blocklist.</p>}
      <button onClick={() => m.mutate()} disabled={m.isPending || !value.trim()} className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 btn-block disabled:opacity-50">
        {m.isPending ? <Loader2 size={13} className="animate-spin" /> : <Ban size={13} />} Add to blocklist
      </button>
    </Card>
  );
}
