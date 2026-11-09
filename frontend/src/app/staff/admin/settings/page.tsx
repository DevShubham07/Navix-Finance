"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ErrorState, Skeleton, toast } from "@/components/ui";
import { ToggleRow } from "@/components/ui/toggle-row";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { PaymentSettingsForm } from "@/components/staff/payment-settings-form";
import { adminApi } from "@/lib/api/applications";
import { intakeSwitchApi } from "@/lib/api/intake-switch";
import { formatDateTime } from "@/lib/utils";

/** Admin · Settings — the onboarding switch and the company payment block. ADMIN only. */
export default function AdminSettingsPage() {
  const myRole = useStaffMe().data?.role;
  const can = useCan();
  const qc = useQueryClient();
  const status = useQuery({ queryKey: ["onboarding-status"], queryFn: adminApi.onboardingStatus });
  const [confirmPause, setConfirmPause] = React.useState(false);

  const setPaused = useMutation({
    mutationFn: (paused: boolean) => adminApi.setOnboardingPaused(paused),
    onSuccess: (s) => {
      setConfirmPause(false);
      qc.setQueryData(["onboarding-status"], s);
      void qc.invalidateQueries({ queryKey: ["feature-flags"] });
      toast.success(s.paused ? "New-customer onboarding paused" : "New-customer onboarding resumed");
    },
  });

  const intake = useQuery({ queryKey: ["intake-switch"], queryFn: intakeSwitchApi.status });
  const [confirmClose, setConfirmClose] = React.useState(false);
  const setClosed = useMutation({
    mutationFn: (closed: boolean) => intakeSwitchApi.setClosed(closed),
    onSuccess: (s) => {
      setConfirmClose(false);
      qc.setQueryData(["intake-switch"], s);
      toast.success(s.closed ? "New applications and reloans closed" : "Applications reopened");
    },
  });

  if (myRole && !can("staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  const s = status.data;

  return (
    <div>
      <PageHeader title="Settings" subtitle="Switches and company details that apply across the console." />

      <section className="mb-8 max-w-2xl rounded border border-line bg-white p-5 shadow-sm">
        <div className="mb-1 text-sm font-semibold text-navy">Onboarding</div>
        <p className="mb-3 text-xs text-muted">
          Pausing sends new (never-borrowed) applicants to the waitlist form. Existing borrowers and
          reborrowing are not affected.
        </p>
        {status.isLoading ? (
          <Skeleton variant="line" rows={2} />
        ) : status.error ? (
          <ErrorState error={status.error} onRetry={() => void status.refetch()} />
        ) : s ? (
          <>
            <ToggleRow
              label="Accept new customers"
              description={s.paused ? "Paused — new applicants join the waitlist." : "On — new applicants can sign up."}
              on={!s.paused}
              disabled={setPaused.isPending}
              onChange={(accept) => (accept ? setPaused.mutate(false) : setConfirmPause(true))}
            />
            {confirmPause && (
              <div role="alertdialog" className="mt-3 rounded border border-warning-500 bg-warning-50 p-3 text-sm">
                <p className="font-semibold text-ink">Pause new-customer onboarding?</p>
                <p className="mt-1 text-xs text-muted">New applicants will see the waitlist form until you turn this back on.</p>
                <div className="mt-3 flex gap-2">
                  <button className="btn btn-navy" disabled={setPaused.isPending} onClick={() => setPaused.mutate(true)}>
                    Yes, pause
                  </button>
                  <button className="btn" disabled={setPaused.isPending} onClick={() => setConfirmPause(false)}>
                    Cancel
                  </button>
                </div>
              </div>
            )}
            {setPaused.error && <p className="mt-2 text-sm text-error-700">{errMessage(setPaused.error)}</p>}
            {s.updatedBy && s.updatedAt && (
              <p className="mt-3 text-xs text-muted">
                Last changed by {s.updatedBy} · {formatDateTime(s.updatedAt)}
              </p>
            )}
          </>
        ) : null}
      </section>

      <section className="mb-8 max-w-2xl rounded border border-line bg-white p-5 shadow-sm">
        <div className="mb-1 text-sm font-semibold text-navy">Applications</div>
        <p className="mb-3 text-xs text-muted">
          Closing stops every new application and reloan. Applications already in progress and active loans carry on.
        </p>
        {intake.isLoading ? (
          <Skeleton variant="line" rows={2} />
        ) : intake.error ? (
          <ErrorState error={intake.error} onRetry={() => void intake.refetch()} />
        ) : intake.data ? (
          <>
            <ToggleRow
              label="Accept applications, including reloans"
              description={
                intake.data.closed
                  ? "Closed. No new applications or reloans are being accepted."
                  : "On. Anyone eligible can start an application or a reloan."
              }
              on={!intake.data.closed}
              disabled={setClosed.isPending}
              onChange={(accept) => (accept ? setClosed.mutate(false) : setConfirmClose(true))}
            />
            {confirmClose && (
              <div role="alertdialog" aria-label="Close all applications" className="mt-3 rounded border border-warning-500 bg-warning-50 p-3 text-sm">
                <p className="font-semibold text-ink">Close all new applications and reloans?</p>
                <ul className="mt-1 list-disc space-y-1 pl-5 text-xs text-muted">
                  <li>Nobody can start a new application or a reloan until you turn this back on.</li>
                  <li>Applications already in progress and active loans carry on as normal.</li>
                  <li>Nothing is cancelled and no messages are sent.</li>
                </ul>
                <div className="mt-3 flex gap-2">
                  <button className="btn btn-navy" disabled={setClosed.isPending} onClick={() => setClosed.mutate(true)}>
                    Yes, close applications
                  </button>
                  <button className="btn" disabled={setClosed.isPending} onClick={() => setConfirmClose(false)}>
                    Keep open
                  </button>
                </div>
              </div>
            )}
            {setClosed.error && <p className="mt-2 text-sm text-error-700">{errMessage(setClosed.error)}</p>}
            {intake.data.updatedBy && intake.data.updatedAt && (
              <p className="mt-3 text-xs text-muted">
                Last changed by {intake.data.updatedBy} · {formatDateTime(intake.data.updatedAt)}
              </p>
            )}
          </>
        ) : null}
      </section>

      <section>
        <h2 className="mb-1 text-lg font-semibold text-navy">Payment settings</h2>
        <p className="mb-3 text-sm text-muted">
          The company payee shown to borrowers on the repay screen — UPI QR, UPI id and bank details.
        </p>
        <PaymentSettingsForm />
      </section>
    </div>
  );
}
