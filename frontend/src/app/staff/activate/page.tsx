"use client";

import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { Loader2, KeyRound, CheckCircle2 } from "lucide-react";
import { Input } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage } from "@/components/staff/live-pipeline";
import { adminApi } from "@/lib/api/applications";

/**
 * Activate a staff account from a one-time invite token (demo: no password yet).
 * The token can be passed via `?token=` or entered manually.
 */
function ActivateInner() {
  const params = useSearchParams();
  const [token, setToken] = React.useState(params.get("token") ?? "");
  const [name, setName] = React.useState("");

  const accept = useMutation({
    mutationFn: () => adminApi.acceptInvite({ token: token.trim(), name: name.trim() }),
  });

  return (
    <div className="mx-auto max-w-md">
      <PageHeader title="Activate your account" subtitle="Use your one-time invite token to activate access." />

      {accept.data ? (
        <div className="rounded border border-success-100 bg-success-50 p-6 text-center shadow-sm">
          <CheckCircle2 size={28} className="mx-auto mb-2 text-success-600" />
          <h3 className="font-serif text-lg text-navy">Account activated</h3>
          <p className="mb-4 text-sm text-success-800">
            Welcome, <strong>{accept.data.name}</strong> — your {accept.data.role} account is now active.
          </p>
          <Link href="/staff/login" className="btn btn-gold">Go to sign in</Link>
        </div>
      ) : (
        <div className="form-card">
          <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-navy"><KeyRound size={16} /> Invite token</div>
          <Input label="Invite token" value={token} onChange={(e) => setToken(e.target.value)} placeholder="paste your one-time token" />
          <Input label="Your name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Aanya Rao" />
          {accept.error && <p className="mb-2 text-sm text-error-700">{errMessage(accept.error)}</p>}
          <button
            onClick={() => accept.mutate()}
            disabled={accept.isPending || !token.trim() || !name.trim()}
            className="btn btn-gold btn-block disabled:opacity-50"
          >
            {accept.isPending ? <Loader2 size={16} className="animate-spin" /> : null} Activate account
          </button>
        </div>
      )}
    </div>
  );
}

export default function StaffActivatePage() {
  return (
    <Suspense fallback={<div className="py-12 text-center text-muted">Loading…</div>}>
      <ActivateInner />
    </Suspense>
  );
}
