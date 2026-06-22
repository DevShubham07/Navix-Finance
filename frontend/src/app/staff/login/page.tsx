"use client";

import * as React from "react";
import { Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ShieldCheck, ArrowRight, Lock } from "lucide-react";
import { Brand } from "@/components/site/brand";
import { STAFF_ROLES, type StaffRole } from "@/lib/auth/rbac";
import { signInStaff, STAFF_ROLE_LABELS, STAFF_PERSONAS } from "@/lib/mock/session";

function LoginInner() {
  const router = useRouter();
  const params = useSearchParams();
  const redirect = params.get("redirect") || "/staff/dashboard";

  const go = (role: StaffRole) => {
    signInStaff(role);
    router.push(redirect);
  };

  return (
    <div className="grid min-h-screen place-items-center px-4 py-10">
      <div className="w-full max-w-xl">
        <div className="mb-6 text-center">
          <div className="mb-4 flex justify-center"><Brand href="/" tag="Staff Console" /></div>
          <h1 className="mb-1">Sign in to the console</h1>
          <p className="text-muted">Internal NAVIX Finance back office. Authorised staff only.</p>
        </div>

        <div className="form-card">
          <div className="mb-4 flex items-center gap-2 text-sm font-semibold text-navy">
            <ShieldCheck size={16} /> Choose a role to continue
            <span className="ml-auto text-xs font-normal text-muted">demo — no password</span>
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            {STAFF_ROLES.map((role) => (
              <button
                key={role}
                onClick={() => go(role)}
                className="group flex items-center justify-between gap-3 rounded border border-line bg-white px-4 py-3 text-left transition hover:border-navy hover:bg-navy-tint"
              >
                <span>
                  <span className="block text-sm font-semibold text-ink">{STAFF_ROLE_LABELS[role]}</span>
                  <span className="block text-xs text-muted">{STAFF_PERSONAS[role].name}</span>
                </span>
                <ArrowRight size={16} className="text-muted transition group-hover:translate-x-0.5 group-hover:text-navy" />
              </button>
            ))}
          </div>
        </div>

        <p className="mt-4 flex items-center justify-center gap-1.5 text-xs text-muted">
          <Lock size={13} /> Production uses SSO + per-role authorisation. Separation of duties is enforced on every decision.
        </p>
      </div>
    </div>
  );
}

export default function StaffLoginPage() {
  return (
    <Suspense fallback={<div className="grid min-h-screen place-items-center text-muted">Loading…</div>}>
      <LoginInner />
    </Suspense>
  );
}
