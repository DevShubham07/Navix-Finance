"use client";

import * as React from "react";
import Link from "next/link";
import { ArrowRight, Loader2, MonitorSmartphone } from "lucide-react";
import { SummarySection } from "@/components/borrower/summary";
import { useMounted } from "@/hooks/use-mounted";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { borrowerApi, type BorrowerPreferences } from "@/lib/api/applications";

/** A single switch row. */
function ToggleRow({
  label,
  description,
  on,
  disabled,
  onChange,
}: {
  label: string;
  description: string;
  on: boolean;
  disabled?: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-grey-200 py-3 last:border-0">
      <div className="min-w-0">
        <div className="text-sm font-semibold text-ink">{label}</div>
        <div className="text-xs text-muted">{description}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-label={label}
        disabled={disabled}
        onClick={() => onChange(!on)}
        className={`relative h-6 w-11 flex-shrink-0 rounded-full transition-colors disabled:opacity-50 ${on ? "bg-navy" : "bg-neutral-300"}`}
      >
        <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all ${on ? "left-[22px]" : "left-0.5"}`} />
      </button>
    </div>
  );
}

/** Device-only preference (genuinely local to this browser/device). */
function useLocalToggle(key: string, defaultValue: boolean): [boolean, (v: boolean) => void] {
  const [value, setValue] = React.useState(defaultValue);
  React.useEffect(() => {
    const raw = window.localStorage.getItem("navix.settings." + key);
    if (raw != null) setValue(raw === "true");
  }, [key]);
  const update = React.useCallback((v: boolean) => {
    setValue(v);
    window.localStorage.setItem("navix.settings." + key, String(v));
  }, [key]);
  return [value, update];
}

export default function SettingsPage() {
  const mounted = useMounted();
  const qc = useQueryClient();

  // Server-persisted notification preferences (honored by the notification engine).
  const prefsQ = useQuery({ queryKey: ["borrower-preferences"], queryFn: () => borrowerApi.getPreferences() });
  const save = useMutation({
    mutationFn: (prefs: BorrowerPreferences) => borrowerApi.updatePreferences(prefs),
    onMutate: (prefs) => {
      qc.setQueryData<BorrowerPreferences>(["borrower-preferences"], prefs); // optimistic
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ["borrower-preferences"] }),
  });
  const prefs = prefsQ.data;
  const setPref = (patch: Partial<BorrowerPreferences>) => {
    if (!prefs) return;
    save.mutate({ ...prefs, ...patch });
  };

  const [biometric, setBiometric] = useLocalToggle("security.biometric", false);

  if (!mounted) {
    return (
      <div className="container max-w-content py-10">
        <div className="h-72 rounded border border-line bg-white" />
      </div>
    );
  }

  return (
    <div className="container max-w-content py-10">
      <div className="mb-7 flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="mb-0">Account settings</h1>
          <p className="mt-1 text-muted">Manage your preferences for this app.</p>
        </div>
        {save.isPending && <span className="flex items-center gap-1 text-xs text-muted"><Loader2 size={13} className="animate-spin" /> Saving…</span>}
      </div>

      <div className="grid gap-4">
        <SummarySection title="Notifications">
          {prefsQ.isLoading || !prefs ? (
            <p className="py-3 text-sm text-muted">Loading…</p>
          ) : (
            <>
              <ToggleRow
                label="Email notifications"
                description="Payment reminders and status updates by email."
                on={prefs.emailOptIn}
                disabled={save.isPending}
                onChange={(v) => setPref({ emailOptIn: v })}
              />
              <ToggleRow
                label="SMS notifications"
                description="Due-date and repayment alerts by text message."
                on={prefs.smsOptIn}
                disabled={save.isPending}
                onChange={(v) => setPref({ smsOptIn: v })}
              />
              <ToggleRow
                label="Product offers"
                description="Occasional pre-approved offers and product news."
                on={prefs.offersOptIn}
                disabled={save.isPending}
                onChange={(v) => setPref({ offersOptIn: v })}
              />
            </>
          )}
          {save.error && <p className="pt-2 text-sm text-error-700">Couldn&apos;t save your preferences. Please try again.</p>}
        </SummarySection>

        <SummarySection title="Device preferences">
          <div className="mb-1 flex items-center gap-1.5 text-xs text-muted">
            <MonitorSmartphone size={13} /> These stay on this device only.
          </div>
          <ToggleRow
            label="Biometric unlock"
            description="Use device biometrics to open the app."
            on={biometric}
            onChange={setBiometric}
          />
        </SummarySection>

        <SummarySection title="Security">
          <div className="flex flex-wrap items-center justify-between gap-3 py-1">
            <p className="text-sm text-muted">Reset your password — we&apos;ll email you a secure reset link.</p>
            <Link href="/forgot-password" className="btn btn-outline btn-sm">
              Reset password <ArrowRight size={15} />
            </Link>
          </div>
        </SummarySection>

        <SummarySection title="Profile">
          <div className="flex flex-wrap items-center justify-between gap-3 py-1">
            <p className="text-sm text-muted">Update your contact, employment, bank and emergency-contact details.</p>
            <Link href="/profile" className="btn btn-outline btn-sm">
              Edit profile <ArrowRight size={15} />
            </Link>
          </div>
        </SummarySection>
      </div>
    </div>
  );
}
