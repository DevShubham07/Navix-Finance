"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Wallet, Smartphone, Landmark, CheckCircle2, ArrowRight, AlertTriangle, Clock } from "lucide-react";
import { Input } from "@/components/ui";
import { InfoRow } from "@/components/borrower/summary";
import { useMounted } from "@/hooks/use-mounted";
import { useLiveApplication } from "@/lib/api/live-journey";
import {
  borrowerApi,
  paymentSettingsApi,
  paiseToINR,
  rupeesToPaise,
  ApplicationApiError,
  type PaymentMethodName,
  type PaymentView,
} from "@/lib/api/applications";
import { formatDate } from "@/lib/utils";

const todayISO = () => new Date().toISOString().slice(0, 10);

export default function RepayPage() {
  const mounted = useMounted();
  const { app, isLoading: appLoading } = useLiveApplication();
  const qc = useQueryClient();

  const loanId =
    app && (app.status === "ACTIVE" || app.status === "OVERDUE" || app.status === "CLOSED")
      ? app.loanId
      : null;

  const [method, setMethod] = React.useState<PaymentMethodName>("UPI");
  const [mode, setMode] = React.useState<"full" | "custom">("full");
  const [custom, setCustom] = React.useState("");
  const [txnRef, setTxnRef] = React.useState("");

  const loanQuery = useQuery({
    queryKey: ["repay-loan", loanId],
    queryFn: () => borrowerApi.loan(loanId as number),
    enabled: loanId != null,
  });
  const outQuery = useQuery({
    queryKey: ["repay-outstanding", loanId],
    queryFn: () => borrowerApi.outstanding(loanId as number, todayISO()),
    enabled: loanId != null,
  });
  const payQuery = useQuery({
    queryKey: ["repay-payments", loanId],
    queryFn: () => borrowerApi.repayments(loanId as number),
    enabled: loanId != null,
  });
  // Admin-managed company payee (UPI QR + bank details). Falls back to the bundled static assets
  // when the backend has no S3 keys yet; presigned URLs are short-lived, so don't cache long.
  const settingsQuery = useQuery({
    queryKey: ["payment-settings"],
    queryFn: () => paymentSettingsApi.get(),
  });

  const record = useMutation({
    mutationFn: (payload: { amountPaise: number; method: PaymentMethodName; txnRef: string }) =>
      borrowerApi.recordRepayment(loanId as number, { ...payload, paidOn: todayISO() }),
    onSuccess: () => {
      setTxnRef("");
      setCustom("");
      qc.invalidateQueries({ queryKey: ["repay-payments", loanId] });
      qc.invalidateQueries({ queryKey: ["repay-outstanding", loanId] });
      qc.invalidateQueries({ queryKey: ["repay-loan", loanId] });
    },
  });

  if (!mounted || (appLoading && !app)) {
    return (
      <div className="container max-w-content py-10">
        <div className="h-72 animate-pulse rounded border border-line bg-white" />
      </div>
    );
  }

  // No live, repayable loan.
  if (loanId == null) {
    return (
      <div className="container max-w-content py-10">
        <div className="rounded border border-line bg-white p-8 text-center shadow-sm">
          <h1 className="text-2xl">No active loan</h1>
          <p className="mb-4 text-muted">You don&apos;t have anything to repay right now.</p>
          <Link href="/dashboard" className="btn btn-gold">Go to dashboard <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  const loan = loanQuery.data;
  if (loanQuery.isLoading || !loan) {
    return (
      <div className="container max-w-content py-10">
        <div className="h-72 animate-pulse rounded border border-line bg-white" />
      </div>
    );
  }

  const settled = loan.status === "CLOSED" || loan.outstandingPaise <= 0;
  if (settled) {
    return (
      <div className="container max-w-content py-12">
        <div className="mx-auto max-w-md rounded-lg border border-success-100 bg-success-50/50 p-10 text-center shadow-md">
          <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-success-50 text-success-600">
            <CheckCircle2 size={34} />
          </span>
          <h1 className="text-2xl">Loan fully repaid</h1>
          <p className="mb-6 text-muted">Thank you — your advance is closed and in good standing.</p>
          <Link href="/dashboard" className="btn btn-gold btn-block">Back to dashboard <ArrowRight size={16} /></Link>
        </div>
      </div>
    );
  }

  // Prepayment-aware "pay today" figure (interest only to today); fall back to the
  // ledger outstanding while it loads.
  const dueToday = outQuery.data?.outstandingPaise ?? loan.outstandingPaise;
  const scheduled = loan.outstandingPaise; // full-tenure basis (minus verified payments)
  const savingPaise = Math.max(0, scheduled - dueToday);
  // An approved collections settlement caps the payable at a full-and-final figure: show it as a
  // settlement (not the normal balance) and suppress the prepay-saving / penalty hints.
  const settlementPaise = outQuery.data?.settledAmountPaise ?? null;
  const isSettlement = settlementPaise != null;
  const overdue = !isSettlement && (app?.status === "OVERDUE" || dueToday > loan.totalRepayablePaise);

  const customPaise = rupeesToPaise(Number(custom.replace(/\D/g, "")) || 0);
  const amountPaise = mode === "full" ? dueToday : Math.min(customPaise, dueToday);
  const canPay = amountPaise > 0 && txnRef.trim().length >= 4 && !record.isPending;

  const pay = () => {
    if (!canPay) return;
    record.mutate({ amountPaise, method, txnRef: txnRef.trim() });
  };

  const payments = payQuery.data ?? [];

  // Payee block — fetched from the admin-managed settings, with the bundled static assets and the
  // previously-hardcoded values as fallbacks so the screen always renders something usable.
  const settings = settingsQuery.data;
  const upiId = settings?.upiId || "navix.collections@hdfcbank";
  const qrSrc = settings?.qrUrl || "/payment/upi-qr.jpg";
  const accountName = settings?.accountName || "NAVIX Finance";
  const accountNumber = settings?.accountNumber || "5010 0099 8877";
  const ifsc = settings?.ifsc || "HDFC0000123";
  const bankName = settings?.bankName || "HDFC Bank";
  const accountInfoHref = settings?.accountInfoUrl || "/payment/account-info.pdf";

  return (
    <div className="container max-w-content py-10">
      <h1 className="mb-1">Repay your advance</h1>
      <p className="mb-7 text-muted">
        Single repayment on your salary day — or prepay any amount, anytime, no penalty.
      </p>

      {record.isSuccess && (
        <div className="mb-6 flex items-start gap-2 rounded border border-success-100 bg-success-50/60 p-4 text-sm text-success-700">
          <Clock size={16} className="mt-0.5 flex-shrink-0" />
          <span>
            Payment recorded — <strong>pending verification</strong> by our accounts team. Your balance
            updates once the transfer is confirmed.
          </span>
        </div>
      )}
      {record.isError && (
        <div className="mb-6 flex items-start gap-2 rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
          <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" />
          {record.error instanceof ApplicationApiError
            ? `${record.error.message} (${record.error.code})`
            : "Could not record your payment. Please try again."}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded border border-line bg-white p-6 shadow-sm">
          <h3 className="mb-2 font-serif text-base text-navy">What you owe</h3>
          <InfoRow label="Principal" value={paiseToINR(loan.principalPaise)} />
          <InfoRow label="Total repayable (on salary day)" value={paiseToINR(loan.totalRepayablePaise)} />
          {loan.dueDate && <InfoRow label="Due date" value={formatDate(loan.dueDate)} />}
          <div className="mt-2 flex items-baseline justify-between rounded bg-navy px-4 py-3 text-white">
            <span className="text-sm font-semibold text-white/90">{isSettlement ? "Settlement — full & final" : overdue ? "Pay now (incl. penalty)" : "Pay today"}</span>
            <span className="font-serif text-2xl font-bold text-gold">{paiseToINR(dueToday)}</span>
          </div>
          {isSettlement && (
            <p className="mt-3 flex items-start gap-2 text-xs text-navy">
              <CheckCircle2 size={14} className="mt-0.5 flex-shrink-0" />
              Our collections team approved a settlement of {paiseToINR(settlementPaise)} as <strong>full &amp; final</strong>.
              Pay this to close your loan — no further amount will be due.
            </p>
          )}
          {savingPaise > 0 && !overdue && !isSettlement && (
            <p className="mt-3 flex items-start gap-2 text-xs text-success-700">
              <CheckCircle2 size={14} className="mt-0.5 flex-shrink-0" />
              Pay early &amp; save {paiseToINR(savingPaise)} — interest is charged only to the day you pay.
            </p>
          )}
          {overdue && (
            <p className="mt-3 flex items-start gap-2 text-xs text-error-600">
              <AlertTriangle size={14} className="mt-0.5 flex-shrink-0" /> A late penalty (2%/day, capped 30 days)
              is accruing. Clear it soon to stop the clock.
            </p>
          )}

          {payments.length > 0 && (
            <div className="mt-5 border-t border-grey-200 pt-4">
              <div className="mb-2 text-sm font-semibold text-ink">Payments</div>
              <ul className="space-y-2">
                {payments.map((p) => (
                  <PaymentRow key={p.id} p={p} />
                ))}
              </ul>
            </div>
          )}
        </div>

        <div className="rounded border border-line bg-white p-6 shadow-sm">
          <h3 className="mb-3 font-serif text-base text-navy">How much to pay</h3>
          <div className="mb-4 grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => setMode("full")}
              className={`rounded border px-3 py-2.5 text-sm font-semibold transition ${mode === "full" ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy"}`}
            >
              Pay in full
            </button>
            <button
              type="button"
              onClick={() => setMode("custom")}
              className={`rounded border px-3 py-2.5 text-sm font-semibold transition ${mode === "custom" ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy"}`}
            >
              Part payment
            </button>
          </div>

          {mode === "custom" && (
            <Input
              label="Amount"
              inputMode="numeric"
              value={custom}
              onChange={(e) => setCustom(e.target.value.replace(/\D/g, ""))}
              placeholder={String(Math.round(dueToday / 100))}
              leftIcon={<span className="font-serif text-muted">₹</span>}
              helperText={`Up to ${paiseToINR(dueToday)}`}
            />
          )}

          <div className="mb-2 mt-1 text-sm font-semibold text-ink">Payment method</div>
          <div className="mb-4 grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => setMethod("UPI")}
              className={`flex items-center justify-center gap-2 rounded border px-3 py-2.5 text-sm font-semibold transition ${method === "UPI" ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy"}`}
            >
              <Smartphone size={16} /> UPI
            </button>
            <button
              type="button"
              onClick={() => setMethod("BANK_TRANSFER")}
              className={`flex items-center justify-center gap-2 rounded border px-3 py-2.5 text-sm font-semibold transition ${method === "BANK_TRANSFER" ? "border-navy bg-navy-tint text-navy" : "border-line text-muted hover:border-navy"}`}
            >
              <Landmark size={16} /> Bank transfer
            </button>
          </div>

          <div className="mb-4 rounded bg-grey-100 p-3 text-xs text-muted">
            {method === "UPI" ? (
              <div className="flex items-center gap-3">
                <div className="group relative flex-shrink-0">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={qrSrc}
                    alt="UPI QR code"
                    className="h-24 w-24 cursor-zoom-in rounded border border-line bg-white object-contain p-1"
                  />
                  {/* Hover-to-enlarge: a large, scannable QR pops up over the small thumbnail. */}
                  <div className="pointer-events-none absolute bottom-full left-0 z-30 mb-2 hidden group-hover:block">
                    <div className="rounded-lg border border-line bg-white p-3 shadow-xl">
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={qrSrc}
                        alt="UPI QR code enlarged"
                        className="h-60 w-60 object-contain"
                      />
                      <p className="mt-2 text-center text-xs text-muted">Scan to pay with any UPI app</p>
                    </div>
                  </div>
                </div>
                <div>
                  Scan the QR or pay to <strong className="text-ink">{upiId}</strong>, then paste the
                  UPI reference below.
                  <span className="mt-1 block text-[11px] text-muted">Hover the QR to enlarge it for scanning.</span>
                </div>
              </div>
            ) : (
              <div className="space-y-0.5">
                <div>NEFT/IMPS to:</div>
                <div><span className="text-ink">{accountName}</span></div>
                <div>A/C <strong className="text-ink">{accountNumber}</strong> · IFSC <strong className="text-ink">{ifsc}</strong></div>
                <div>{bankName}</div>
                <a
                  href={accountInfoHref}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="mt-1 inline-flex items-center gap-1 font-semibold text-navy underline-offset-2 hover:underline"
                >
                  <Landmark size={13} /> Download account details (PDF)
                </a>
                <div className="pt-1">Then paste the UTR below.</div>
              </div>
            )}
          </div>

          <Input
            label={method === "UPI" ? "UPI reference / txn ID" : "UTR number"}
            value={txnRef}
            onChange={(e) => setTxnRef(e.target.value)}
            placeholder={method === "UPI" ? "e.g. 4287…" : "e.g. HDFCN…"}
            helperText="We verify your payment against this reference before it reduces your balance"
          />

          <button onClick={pay} disabled={!canPay} className="btn btn-gold btn-block mt-2">
            <Wallet size={16} /> {record.isPending ? "Recording…" : `Pay ${paiseToINR(amountPaise)}`}
          </button>
          <p className="mt-3 text-center text-xs text-muted">
            Payments are confirmed manually by our accounts team — your balance updates once verified.
          </p>
        </div>
      </div>
    </div>
  );
}

function PaymentRow({ p }: { p: PaymentView }) {
  const map: Record<PaymentView["status"], { label: string; cls: string }> = {
    PENDING_VERIFICATION: { label: "Pending verification", cls: "bg-gold-50 text-gold-dark" },
    VERIFIED: { label: "Verified", cls: "bg-success-50 text-success-700" },
    REJECTED: { label: "Rejected", cls: "bg-error-50 text-error-700" },
  };
  const s = map[p.status];
  return (
    <li className="flex items-center justify-between gap-3 text-sm">
      <span className="flex items-center gap-2">
        <span className="font-semibold text-ink">{paiseToINR(p.amountPaise)}</span>
        <span className="text-xs text-muted">
          {p.method === "UPI" ? "UPI" : "Bank"}
          {p.txnRef ? ` · ${p.txnRef}` : ""}
          {p.paidOn ? ` · ${formatDate(p.paidOn)}` : ""}
        </span>
      </span>
      <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${s.cls}`}>{s.label}</span>
    </li>
  );
}
