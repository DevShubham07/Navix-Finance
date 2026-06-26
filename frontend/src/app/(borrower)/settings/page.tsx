"use client";

import * as React from "react";
import Link from "next/link";
import { ArrowRight, Info } from "lucide-react";
import { SummarySection } from "@/components/borrower/summary";
import { useMounted } from "@/hooks/use-mounted";

/**
 * Account settings — demo-grade. Preferences are stored in this browser's
 * localStorage only; nothing is sent to the server. Mirrors the profile page's
 * SummarySection layout.
 */

const STORAGE_PREFIX = "navix.settings.";

type ToggleDef = { key: string; label: string; description: string; default: boolean };

const NOTIFICATIONS: ToggleDef[] = [
  { key: "notif.email", label: "Email notifications", description: "Payment reminders and status updates by email.", default: true },
  { key: "notif.sms", label: "SMS notifications", description: "Due-date and repayment alerts by text message.", default: true },
  { key: "notif.offers", label: "Product offers", description: "Occasional pre-approved offers and product news.", default: false },
];

const SECURITY: ToggleDef[] = [
  { key: "security.loginAlerts", label: "Login alerts", description: "Notify me when my account is accessed from a new device.", default: true },
  { key: "security.biometric", label: "Biometric unlock", description: "Use device biometrics to open the app.", default: false },
];

function useLocalToggle(key: string, defaultValue: boolean): [boolean, (v: boolean) => void] {
  const [value, setValue] = React.useState(defaultValue);
  React.useEffect(() => {
    const raw = window.localStorage.getItem(STORAGE_PREFIX + key);
    if (raw != null) setValue(raw === "true");
  }, [key]);
  const update = React.useCallback(
    (v: boolean) => {
      setValue(v);
      window.localStorage.setItem(STORAGE_PREFIX + key, String(v));
    },
    [key],
  );
  return [value, update];
}

function Toggle({ def }: { def: ToggleDef }) {
  const [on, setOn] = useLocalToggle(def.key, def.default);
  return (
    <div className="flex items-center justify-between gap-4 border-b border-grey-200 py-3 last:border-0">
      <div className="min-w-0">
        <div className="text-sm font-semibold text-ink">{def.label}</div>
        <div className="text-xs text-muted">{def.description}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-label={def.label}
        onClick={() => setOn(!on)}
        className={`relative h-6 w-11 flex-shrink-0 rounded-full transition-colors ${on ? "bg-navy" : "bg-neutral-300"}`}
      >
        <span
          className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-all ${on ? "left-[22px]" : "left-0.5"}`}
        />
      </button>
    </div>
  );
}

export default function SettingsPage() {
  const mounted = useMounted();

  if (!mounted) {
    return (
      <div className="container max-w-content py-10">
        <div className="h-72 rounded border border-line bg-white" />
      </div>
    );
  }

  return (
    <div className="container max-w-content py-10">
      <div className="mb-7">
        <h1 className="mb-0">Account settings</h1>
        <p className="mt-1 text-muted">Manage your preferences for this app.</p>
      </div>

      <div className="mb-4 flex items-start gap-2 rounded border border-gold-soft bg-gold-50/60 px-4 py-3 text-xs text-ink">
        <Info size={15} className="mt-0.5 flex-shrink-0 text-gold-dark" />
        <span>
          <strong>Demo settings.</strong> Your choices are saved only in this browser and don&apos;t change anything on
          the server.
        </span>
      </div>

      <div className="grid gap-4">
        <SummarySection title="Notifications">
          {NOTIFICATIONS.map((t) => (
            <Toggle key={t.key} def={t} />
          ))}
        </SummarySection>

        <SummarySection title="Security">
          {SECURITY.map((t) => (
            <Toggle key={t.key} def={t} />
          ))}
        </SummarySection>

        <SummarySection title="Profile">
          <div className="flex flex-wrap items-center justify-between gap-3 py-1">
            <p className="text-sm text-muted">Update your personal, employment and bank details.</p>
            <Link href="/profile" className="btn btn-outline btn-sm">
              Edit profile <ArrowRight size={15} />
            </Link>
          </div>
        </SummarySection>
      </div>
    </div>
  );
}
