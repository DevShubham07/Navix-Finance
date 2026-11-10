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
import { paymentGatewayApi } from "@/lib/api/payment-gateway";
import { formatDateTime } from "@/lib/utils";

/** Admin · Settings — the onboarding/application switches, the payment-gateway switch and the company payment block. ADMIN only. */
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

  const gateway = useQuery({ queryKey: ["payment-gateway"], queryFn: paymentGatewayApi.status });
  const [confirmGatewayOff, setConfirmGatewayOff] = React.useState(false);
  const setGateway = useMutation({
    mutationFn: (enabled: boolean) => paymentGatewayApi.setEnabled(enabled),
    onSuccess: (s) => {
      setConfirmGatewayOff(false);
      qc.setQueryData(["payment-gateway"], s);
      toast.success(s.enabled ? "Payment gateway turned on" : "Payment gateway turned off — borrowers pay manually");
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

      <section className="mb-8 max-w-2xl rounded border border-line bg-white p-5 shadow-sm">
        <div className="mb-1 text-sm font-semibold text-navy">Payment gateway</div>
        <p className="mb-3 text-xs text-muted">
          On, borrowers repay online through the PayU checkout. Off, the repay screen goes back to direct payment:
          the UPI QR / bank details below plus a payment screenshot, verified by the accounts team.
        </p>
        {gateway.isLoading ? (
          <Skeleton variant="line" rows={2} />
        ) : gateway.error ? (
          <ErrorState error={gateway.error} onRetry={() => void gateway.refetch()} />
        ) : gateway.data ? (
          <>
            <ToggleRow
              label="Enable payment gateway"
              description={
                gateway.data.enabled
                  ? "On. Borrowers pay online through PayU."
                  : gateway.data.configured
                    ? "Off. Borrowers pay directly by UPI or bank transfer and upload a screenshot."
                    : "Off. The gateway's merchant credentials are not set up on this server, so it cannot be turned on."
              }
              on={gateway.data.enabled}
              disabled={setGateway.isPending || (!gateway.data.enabled && !gateway.data.configured)}
              onChange={(on) => (on ? setGateway.mutate(true) : setConfirmGatewayOff(true))}
            />
            {confirmGatewayOff && (
              <div role="alertdialog" aria-label="Turn off payment gateway" className="mt-3 rounded border border-warning-500 bg-warning-50 p-3 text-sm">
                <p className="font-semibold text-ink">Turn off the payment gateway?</p>
                <ul className="mt-1 list-disc space-y-1 pl-5 text-xs text-muted">
                  <li>Borrowers will see the direct payment option (UPI / bank details + screenshot) instead of online checkout.</li>
                  <li>Payments already started at PayU are still confirmed and credited.</li>
                  <li>Manual payments need the accounts team to verify them.</li>
                </ul>
                <div className="mt-3 flex gap-2">
                  <button className="btn btn-navy" disabled={setGateway.isPending} onClick={() => setGateway.mutate(false)}>
                    Yes, turn off
                  </button>
                  <button className="btn" disabled={setGateway.isPending} onClick={() => setConfirmGatewayOff(false)}>
                    Keep on
                  </button>
                </div>
              </div>
            )}
            {setGateway.error && <p className="mt-2 text-sm text-error-700">{errMessage(setGateway.error)}</p>}
            {gateway.data.updatedBy && gateway.data.updatedAt && (
              <p className="mt-3 text-xs text-muted">
                Last changed by {gateway.data.updatedBy} · {formatDateTime(gateway.data.updatedAt)}
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
