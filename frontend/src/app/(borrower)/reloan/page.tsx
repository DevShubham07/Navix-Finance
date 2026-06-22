"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { RotateCw, Zap, ShieldCheck, ArrowRight } from "lucide-react";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { useMounted } from "@/hooks/use-mounted";
import { formatINR0 } from "@/lib/utils";

export default function ReloanPage() {
  const router = useRouter();
  const mounted = useMounted();
  const j = useBorrowerJourney();

  if (!mounted) {
    return <div className="container max-w-content py-10"><div className="h-72 rounded border border-line bg-white" /></div>;
  }

  const active = j.status === "ACTIVE" || j.status === "OVERDUE";

  if (active) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">You already have an active advance</h1>
          <p className="mb-4 text-muted">Repay your current loan before borrowing again.</p>
          <Link href="/repay" className="btn btn-gold">Go to repayment <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const eligible = j.status === "REPAID" && j.applicant.monthlySalary > 0;

  if (!eligible) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">Welcome back</h1>
          <p className="mb-4 text-muted">Start a fresh application to borrow with NAVIX.</p>
          <Link href="/signup/pan" className="btn btn-gold">Apply now <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const borrowAgain = () => {
    j.reborrow();
    router.push("/loan/apply");
  };

  return (
    <div className="container max-w-content py-10">
      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-gold-dark">
        <Zap size={14} /> Pre-approved
      </div>
      <h1 className="mb-1">Borrow again, instantly</h1>
      <p className="mb-6 text-muted">
        Your repayment history is clean, so you skip full KYC — just light re-checks and you&apos;re funded.
      </p>

      <div className="rounded border border-gold-soft bg-gold-50/50 p-7 text-center shadow-sm">
        <div className="text-sm text-muted">Available to borrow now</div>
        <div className="my-1 font-serif text-4xl font-bold text-navy">{formatINR0(j.sanctionedLimit())}</div>
        <div className="mb-5 text-sm text-muted">Up to 25% of your {formatINR0(j.applicant.monthlySalary)} salary</div>
        <button onClick={borrowAgain} className="btn btn-gold">
          <RotateCw size={16} /> Borrow again
        </button>
      </div>

      <ul className="mt-6 grid gap-3 sm:grid-cols-3">
        {[
          { icon: <Zap size={18} />, t: "Light re-checks", s: "Bank & bureau refresh only" },
          { icon: <ShieldCheck size={18} />, t: "No full KYC", s: "Your verified details carry over" },
          { icon: <ArrowRight size={18} />, t: "Same fast flow", s: "Amount → sign → disburse" },
        ].map((f) => (
          <li key={f.t} className="rounded border border-line bg-white p-4 text-sm shadow-sm">
            <span className="mb-2 grid h-9 w-9 place-items-center rounded bg-navy-tint text-navy">{f.icon}</span>
            <div className="font-semibold text-ink">{f.t}</div>
            <div className="text-xs text-muted">{f.s}</div>
          </li>
        ))}
      </ul>
    </div>
  );
}
