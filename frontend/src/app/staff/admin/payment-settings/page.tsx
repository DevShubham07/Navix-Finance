"use client";

import * as React from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2, RefreshCw, QrCode, FileText, Save, CheckCircle2 } from "lucide-react";
import { Input, ZoomableQr } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { errMessage, useStaffMe, NoAccessNotice } from "@/components/staff/live-pipeline";
import { hasPermission } from "@/lib/auth/rbac";
import {
  paymentSettingsApi,
  storageApi,
  type UpdatePaymentSettingsInput,
} from "@/lib/api/applications";

/**
 * Admin · company payment block — edit the payee shown on the borrower repay screen (UPI id + bank
 * details), and upload a UPI QR image + payee account-info PDF. Live /api/payment-settings. ADMIN only.
 */
export default function AdminPaymentSettingsPage() {
  const myRole = useStaffMe().data?.role;
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["payment-settings"], queryFn: paymentSettingsApi.get });

  const [form, setForm] = React.useState<Record<
    "upiId" | "accountName" | "accountNumber" | "ifsc" | "bankName",
    string
  >>({
    upiId: "",
    accountName: "",
    accountNumber: "",
    ifsc: "",
    bankName: "",
  });
  const [qrFile, setQrFile] = React.useState<File | null>(null);
  const [pdfFile, setPdfFile] = React.useState<File | null>(null);
  const [loaded, setLoaded] = React.useState(false);

  // Seed the form once the current settings arrive.
  React.useEffect(() => {
    if (q.data && !loaded) {
      setForm({
        upiId: q.data.upiId ?? "",
        accountName: q.data.accountName ?? "",
        accountNumber: q.data.accountNumber ?? "",
        ifsc: q.data.ifsc ?? "",
        bankName: q.data.bankName ?? "",
      });
      setLoaded(true);
    }
  }, [q.data, loaded]);

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }));

  const save = useMutation({
    mutationFn: async () => {
      // Upload any newly-selected assets, then persist the keys alongside the text fields.
      const body: UpdatePaymentSettingsInput = { ...form };
      if (qrFile) {
        const up = await storageApi.presignUpload({
          category: "PAYMENT_SETTINGS",
          filename: qrFile.name,
          contentType: qrFile.type || "image/jpeg",
        });
        await storageApi.putToPresignedUrl(up.url, qrFile);
        body.qrObjectKey = up.key;
      }
      if (pdfFile) {
        const up = await storageApi.presignUpload({
          category: "PAYMENT_SETTINGS",
          filename: pdfFile.name,
          contentType: pdfFile.type || "application/pdf",
        });
        await storageApi.putToPresignedUrl(up.url, pdfFile);
        body.accountInfoObjectKey = up.key;
      }
      return paymentSettingsApi.update(body);
    },
    onSuccess: () => {
      setQrFile(null);
      setPdfFile(null);
      qc.invalidateQueries({ queryKey: ["payment-settings"] });
    },
  });

  if (myRole && !hasPermission(myRole, "staff:manage")) {
    return <NoAccessNotice message="Admin access only." />;
  }

  const current = q.data;

  return (
    <div>
      <PageHeader
        title="Payment settings"
        subtitle="The company payee shown to borrowers on the repay screen — UPI QR, UPI id and bank details."
      >
        <button
          onClick={() => q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      {q.isLoading ? (
        <div className="h-64 animate-pulse rounded border border-line bg-white" />
      ) : q.error ? (
        <p className="text-sm text-error-700">{errMessage(q.error)}</p>
      ) : (
        <div className="grid gap-6 lg:grid-cols-2">
          {/* Editable fields */}
          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <div className="mb-4 text-sm font-semibold text-navy">Payee details</div>
            <Input label="UPI id" value={form.upiId} onChange={set("upiId")} placeholder="navix.collections@hdfcbank" />
            <Input label="Account name" value={form.accountName} onChange={set("accountName")} placeholder="DhanBoost" />
            <Input label="Account number" value={form.accountNumber} onChange={set("accountNumber")} placeholder="5010 0099 8877" />
            <Input label="IFSC" value={form.ifsc} onChange={set("ifsc")} placeholder="HDFC0000123" />
            <Input label="Bank name" value={form.bankName} onChange={set("bankName")} placeholder="HDFC Bank" />

            <div className="mt-4 space-y-3">
              <label className="block">
                <span className="mb-1 flex items-center gap-1.5 text-sm font-semibold text-ink"><QrCode size={15} /> UPI QR image</span>
                <input
                  type="file"
                  accept="image/*"
                  onChange={(e) => setQrFile(e.target.files?.[0] ?? null)}
                  className="block w-full text-sm text-muted file:mr-3 file:rounded file:border file:border-line file:bg-grey-50 file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-navy hover:file:bg-grey-100"
                />
                {qrFile && <span className="mt-1 block text-xs text-success-700">Selected: {qrFile.name}</span>}
              </label>
              <label className="block">
                <span className="mb-1 flex items-center gap-1.5 text-sm font-semibold text-ink"><FileText size={15} /> Account-info PDF</span>
                <input
                  type="file"
                  accept="application/pdf"
                  onChange={(e) => setPdfFile(e.target.files?.[0] ?? null)}
                  className="block w-full text-sm text-muted file:mr-3 file:rounded file:border file:border-line file:bg-grey-50 file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-navy hover:file:bg-grey-100"
                />
                {pdfFile && <span className="mt-1 block text-xs text-success-700">Selected: {pdfFile.name}</span>}
              </label>
            </div>

            <button onClick={() => save.mutate()} disabled={save.isPending} className="btn btn-gold mt-5 disabled:opacity-50">
              {save.isPending ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />} Save changes
            </button>
            {save.isSuccess && (
              <p className="mt-2 flex items-center gap-1.5 text-sm text-success-700"><CheckCircle2 size={14} /> Saved.</p>
            )}
            {save.error && <p className="mt-2 text-sm text-error-700">{errMessage(save.error)}</p>}
          </div>

          {/* Current preview (what borrowers see) */}
          <div className="rounded border border-line bg-white p-5 shadow-sm">
            <div className="mb-4 text-sm font-semibold text-navy">Current payee (borrower view)</div>
            <dl className="space-y-2 text-sm">
              <Row label="UPI id" value={current?.upiId} />
              <Row label="Account name" value={current?.accountName} />
              <Row label="Account number" value={current?.accountNumber} />
              <Row label="IFSC" value={current?.ifsc} />
              <Row label="Bank name" value={current?.bankName} />
            </dl>
            <div className="mt-4 grid grid-cols-2 gap-3">
              <div>
                <div className="mb-1 text-xs font-semibold text-muted">UPI QR <span className="font-normal text-muted/70">(hover to enlarge)</span></div>
                <ZoomableQr
                  src={current?.qrUrl || "/payment/upi-qr.jpg"}
                  thumbClassName="h-32 w-32 rounded border border-line bg-white object-contain p-1"
                />
                {!current?.qrUrl && <p className="mt-1 text-xs text-muted">Static fallback (no upload yet)</p>}
              </div>
              <div>
                <div className="mb-1 text-xs font-semibold text-muted">Account info</div>
                <a
                  href={current?.accountInfoUrl || "/payment/account-info.pdf"}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-sm font-semibold text-navy hover:bg-grey-100"
                >
                  <FileText size={14} /> View PDF
                </a>
                {!current?.accountInfoUrl && <p className="mt-1 text-xs text-muted">Static fallback (no upload yet)</p>}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-muted">{label}</dt>
      <dd className="font-medium text-ink">{value || "—"}</dd>
    </div>
  );
}
