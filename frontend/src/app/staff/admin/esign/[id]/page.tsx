"use client";

import * as React from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Copy, Download, ExternalLink, Loader2, Mail, PenLine, RefreshCw } from "lucide-react";
import { Badge, ErrorState, Skeleton, toast } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { useStaffMe, useCan, NoAccessNotice } from "@/components/staff/live-pipeline";
import { esignDocsApi, type EsignDocDetail } from "@/lib/api/applications";
import { DOC_STATUS_VARIANT, esignErrMessage } from "@/lib/esign/esign-ui";
import { POSITIONS } from "@/lib/esign/validate-esign-form";

const POLL_MS = 10_000;
const positionLabel = (v: string) => POSITIONS.find((p) => p.value === v)?.label ?? v;

function fmt(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" }) : "—";
}

/** Admin · one eSign document — signer links/status, downloads, refresh + 10 s poll while SENT. */
export default function EsignDetailPage() {
  const { id } = useParams<{ id: string }>();
  const docId = Number(id);
  const me = useStaffMe().data;
  const can = useCan();
  const qc = useQueryClient();
  const key = ["esign-doc", docId];

  const q = useQuery({
    queryKey: key,
    queryFn: () => esignDocsApi.get(docId),
    // Poll only while the contract is still out for signature.
    refetchInterval: (query) => (query.state.data?.status === "SENT" ? POLL_MS : false),
  });

  const setDetail = (d: EsignDocDetail) => {
    qc.setQueryData(key, d);
    void qc.invalidateQueries({ queryKey: ["esign-docs"] });
  };
  const refresh = useMutation({ mutationFn: () => esignDocsApi.refresh(docId), onSuccess: setDetail });
  const resend = useMutation({
    mutationFn: (signerId: number) => esignDocsApi.resend(docId, signerId),
    onSuccess: (d) => {
      setDetail(d);
      toast.success("Email re-sent");
    },
  });

  if (me?.role && !can("staff:manage")) return <NoAccessNotice message="Admin access only." />;

  const copy = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url);
      toast.success("Signing link copied");
    } catch {
      toast.error("Could not copy — select the link manually.");
    }
  };

  const d = q.data;
  const actionErr = refresh.error ?? resend.error;

  return (
    <div>
      <Link href="/staff/admin/esign" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft size={14} /> All documents
      </Link>

      {q.isLoading ? (
        <Skeleton variant="table" rows={4} cols={5} className="rounded border border-line bg-white" />
      ) : q.error || !d ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <>
          <PageHeader title={d.title} subtitle={`${d.fileName} · ${d.pageCount} page${d.pageCount === 1 ? "" : "s"} · created ${fmt(d.createdAt)}`}>
            <Badge variant={DOC_STATUS_VARIANT[d.status] ?? "neutral"}>{d.status}</Badge>
            <button
              onClick={() => refresh.mutate()}
              disabled={refresh.isPending}
              className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink disabled:opacity-50"
            >
              {refresh.isPending ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
            </button>
          </PageHeader>

          {d.errorMessage && <p className="mb-3 rounded border border-line bg-white p-3 text-sm text-error-700">{d.errorMessage}</p>}
          {actionErr && <p className="mb-3 text-sm text-error-700">{esignErrMessage(actionErr)}</p>}
          {d.status === "SENT" && (
            <p className="mb-3 text-xs text-muted">Waiting for signatures — this page updates automatically every 10 seconds.</p>
          )}

          <div className="mb-4 flex flex-wrap gap-3">
            <DownloadLink href={d.originalUrl} label="Original PDF" />
            {d.signedUrl && <DownloadLink href={d.signedUrl} label="Signed PDF" />}
            {d.auditCertificateUrl && <DownloadLink href={d.auditCertificateUrl} label="Audit certificate" />}
          </div>

          <div className="overflow-hidden rounded border border-line bg-white shadow-sm">
            <div className="staff-table-scroll">
              <table className="staff-data-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Signer</th>
                    <th>Placement</th>
                    <th>Status</th>
                    <th>Email</th>
                    <th className="text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line align-top">
                  {d.signers.map((s) => (
                    <tr key={s.id}>
                      <td className="text-muted">{s.order}</td>
                      <td>
                        <div className="font-medium text-ink">{s.name}{s.self ? " (you)" : ""}</div>
                        {s.email && <div className="text-xs text-muted">{s.email}</div>}
                      </td>
                      <td className="text-muted">
                        <div>Pages: {s.pages}</div>
                        <div className="text-xs">{positionLabel(s.position)}</div>
                      </td>
                      <td>
                        <Badge variant={DOC_STATUS_VARIANT[s.status] ?? "neutral"}>{s.status}</Badge>
                        {s.signedAt && <div className="mt-1 text-xs text-muted">{fmt(s.signedAt)}</div>}
                        {s.errorMessage && <div className="mt-1 text-xs text-error-700">{s.errorMessage}</div>}
                      </td>
                      <td>
                        <Badge variant={DOC_STATUS_VARIANT[s.emailStatus] ?? "error"}>{s.emailStatus.replace("_", " ")}</Badge>
                        {s.emailSentAt && <div className="mt-1 text-xs text-muted">{fmt(s.emailSentAt)}</div>}
                      </td>
                      <td className="text-right">
                        <div className="flex flex-wrap justify-end gap-2">
                          {s.self && s.signUrl && s.status === "PENDING" && (
                            <button className="btn btn-sm btn-gold" onClick={() => window.open(s.signUrl!, "_blank", "noopener,noreferrer")}>
                              <PenLine size={13} /> Sign now
                            </button>
                          )}
                          {s.signUrl && s.status === "PENDING" && (
                            <button className="btn btn-sm btn-outline" onClick={() => void copy(s.signUrl!)}>
                              <Copy size={13} /> Copy link
                            </button>
                          )}
                          {!s.self && s.email && s.status === "PENDING" && d.status === "SENT" && (
                            <button
                              className="btn btn-sm btn-outline disabled:opacity-50"
                              disabled={resend.isPending}
                              onClick={() => resend.mutate(s.id)}
                            >
                              <Mail size={13} /> Resend email
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function DownloadLink({ href, label }: { href: string; label: string }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-center gap-1.5 rounded border border-line bg-white px-3 py-1.5 text-sm font-medium text-navy hover:bg-grey-50"
    >
      <Download size={13} /> {label} <ExternalLink size={11} className="text-muted" />
    </a>
  );
}
