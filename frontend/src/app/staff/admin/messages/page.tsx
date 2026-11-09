"use client";

import * as React from "react";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { Loader2, RefreshCw } from "lucide-react";
import { EmptyState, ErrorState, Select, Skeleton } from "@/components/ui";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice, PermissionGate } from "@/components/staff/live-pipeline";
import { ExportMenu } from "@/components/staff/export-menu";
import { PaginationBar } from "@/components/staff/pipeline/pagination";
import type { ExportColumn } from "@/lib/export/exporters";
import { customerMessagesApi, type MessageLogFilters, type MessageLogRow } from "@/lib/api/applications";
import { formatDate, formatDateTime } from "@/lib/utils";

const CHUNK = 100;
const CHANNEL_LABEL: Record<MessageLogRow["channel"], string> = { SMS: "SMS", WHATSAPP: "WhatsApp", EMAIL: "Email" };

const EXPORT_COLUMNS: ExportColumn<MessageLogRow>[] = [
  { header: "Customer ID", value: (r) => r.customerId },
  { header: "Customer", value: (r) => r.customerName ?? "" },
  { header: "Template", value: (r) => r.templateKey },
  { header: "Channel", value: (r) => r.channel },
  { header: "Discount %", value: (r) => r.discountPercent ?? "" },
  { header: "Repay by", value: (r) => r.repayBy ?? "" },
  { header: "Status", value: (r) => r.status },
  { header: "Reason", value: (r) => r.reason ?? "" },
  { header: "Sent by", value: (r) => r.sentByName ?? "" },
  { header: "Time", value: (r) => r.createdAt },
  { header: "Mobile or email (masked)", value: (r) => r.mobile ?? "" },
];

const TONE: Record<MessageLogRow["status"], string> = {
  SENT: "text-success-700",
  FAILED: "text-error-700",
  SKIPPED: "text-warning-700",
};

/** Admin · customer messages — every SMS / WhatsApp the console sent (or skipped), newest first. */
export default function AdminMessagesPage() {
  return (
    <PermissionGate permission="message:send" fallback={<NoAccessNotice message="Admin access only." />}>
      <MessageLog />
    </PermissionGate>
  );
}

function MessageLog() {
  const [page, setPage] = React.useState(1);
  const [size, setSize] = React.useState(25);
  const [templateKey, setTemplateKey] = React.useState("");
  const [channel, setChannel] = React.useState("");
  const [status, setStatus] = React.useState("");
  const [from, setFrom] = React.useState("");
  const [to, setTo] = React.useState("");
  const [customerId, setCustomerId] = React.useState("");

  const cid = customerId.trim() === "" ? undefined : Number(customerId);
  const filters: MessageLogFilters = {
    templateKey: templateKey || undefined,
    channel: channel || undefined,
    status: status || undefined,
    from: from || undefined,
    to: to || undefined,
    customerId: cid != null && Number.isFinite(cid) ? cid : undefined,
  };
  // Any filter change goes back to page 1.
  const filterKey = JSON.stringify(filters);
  React.useEffect(() => setPage(1), [filterKey]);

  const templatesQ = useQuery({
    queryKey: ["customer-message-templates"],
    queryFn: () => customerMessagesApi.templates(),
    staleTime: 5 * 60_000,
  });
  const q = useQuery({
    queryKey: ["customer-message-log", filterKey, page, size],
    queryFn: () => customerMessagesApi.log({ ...filters, page, size }),
    placeholderData: keepPreviousData,
  });

  const getAllRows = async () => {
    const out: MessageLogRow[] = [];
    for (let p = 1; ; p++) {
      const res = await customerMessagesApi.log({ ...filters, page: p, size: CHUNK });
      out.push(...res.rows);
      if (res.rows.length === 0 || out.length >= res.total) return out;
    }
  };

  const rows = q.data?.rows ?? [];
  const total = q.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / size));
  const labelFor = (key: string) => templatesQ.data?.find((t) => t.key === key)?.label ?? key;

  return (
    <div>
      <PageHeader title="Customer messages" subtitle="Every SMS, WhatsApp and email sent from the console, with the result. Admin only.">
        <ExportMenu
          title="Customer messages"
          fileBase="dhanboost-customer-messages"
          columns={EXPORT_COLUMNS}
          rows={rows}
          scope="page"
          getAllRows={getAllRows}
        />
        <button
          type="button"
          onClick={() => void q.refetch()}
          className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink"
        >
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-end gap-2">
        <Select aria-label="Template" value={templateKey} onChange={(e) => setTemplateKey(e.target.value)} className="!mb-0">
          <option value="">All templates</option>
          {(templatesQ.data ?? []).map((t) => (
            <option key={t.key} value={t.key}>
              {t.label}
            </option>
          ))}
        </Select>
        <Select
          aria-label="Channel"
          value={channel}
          onChange={(e) => setChannel(e.target.value)}
          className="!mb-0"
          options={[
            { value: "", label: "All channels" },
            { value: "SMS", label: "SMS" },
            { value: "WHATSAPP", label: "WhatsApp" },
            { value: "EMAIL", label: "Email" },
          ]}
        />
        <Select
          aria-label="Status"
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          className="!mb-0"
          options={[
            { value: "", label: "All results" },
            { value: "SENT", label: "Sent" },
            { value: "FAILED", label: "Failed" },
            { value: "SKIPPED", label: "Skipped" },
          ]}
        />
        <label className="flex flex-col text-xs text-muted">
          From
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="rounded border border-line px-2 py-1 text-sm" />
        </label>
        <label className="flex flex-col text-xs text-muted">
          To
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="rounded border border-line px-2 py-1 text-sm" />
        </label>
        <label className="flex flex-col text-xs text-muted">
          Customer ID
          <input
            inputMode="numeric"
            value={customerId}
            onChange={(e) => setCustomerId(e.target.value.replace(/\D/g, ""))}
            className="w-28 rounded border border-line px-2 py-1 text-sm"
          />
        </label>
      </div>

      {q.isLoading ? (
        <Skeleton variant="table" rows={8} cols={10} className="rounded border border-line bg-white" />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <div className="overflow-hidden rounded border border-line bg-white shadow-sm" aria-busy={q.isPlaceholderData}>
          <div className={`staff-table-scroll${q.isPlaceholderData ? " opacity-60" : ""}`}>
            <table className="staff-data-table">
              <caption className="sr-only">Customer messages</caption>
              <thead>
                <tr>
                  <th scope="col">Customer</th>
                  <th scope="col">Template</th>
                  <th scope="col">Channel</th>
                  <th scope="col">Discount</th>
                  <th scope="col">Repay by</th>
                  <th scope="col">Status</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Sent by</th>
                  <th scope="col">Time</th>
                  <th scope="col">Sent to</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line align-top">
                {rows.map((r) => (
                  <tr key={r.id} className="hover:bg-grey-50">
                    <td>
                      <span className="block font-semibold text-ink">{r.customerName || "—"}</span>
                      <span className="block text-xs text-muted">#{r.customerId}</span>
                    </td>
                    <td className="whitespace-nowrap">{labelFor(r.templateKey)}</td>
                    <td className="whitespace-nowrap">{CHANNEL_LABEL[r.channel]}</td>
                    <td className="whitespace-nowrap">{r.discountPercent != null ? `${r.discountPercent}%` : "—"}</td>
                    <td className="whitespace-nowrap">{r.repayBy ? formatDate(r.repayBy) : "—"}</td>
                    <td className={`whitespace-nowrap font-semibold ${TONE[r.status]}`}>{r.status.toLowerCase()}</td>
                    <td className="max-w-[16rem] text-muted">{r.reason || "—"}</td>
                    <td className="whitespace-nowrap">{r.sentByName || "—"}</td>
                    <td className="whitespace-nowrap text-muted">{formatDateTime(r.createdAt)}</td>
                    <td className="whitespace-nowrap font-mono text-muted">{r.mobile || "—"}</td>
                  </tr>
                ))}
                {rows.length === 0 && <EmptyState title="No messages match." inTable={10} />}
              </tbody>
            </table>
          </div>
          <PaginationBar page={page} pageCount={pageCount} setPage={setPage} total={total} pageSize={size} setPageSize={setSize} />
        </div>
      )}
    </div>
  );
}
