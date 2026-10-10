"use client";

/**
 * Mobile-number reuse surfaces (customer pop-up v5): bureau-listed numbers on the money tabs, the red
 * "used in N other cases" strip, the where-it-appears drawer, and the Dedupe-tab section. All read one
 * cached query. Staff-only; out-of-book matches arrive without identifiers and get no navigation.
 */

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, Phone, PhoneCall } from "lucide-react";
import { Badge, Skeleton } from "@/components/ui";
import { Section } from "@/components/staff/detail-parts";
import { Drawer, DrawerBody, DrawerHeader, DrawerTitle } from "@/components/ui/drawer";
import { customersApi, statusLabel, type ApplicationStatus, type MobileMatch, type MobileMatchView, type OurNumber } from "@/lib/api/applications";
import { customerPageHref } from "@/lib/customers/customer-page";
import { cn, formatDate } from "@/lib/utils";

export function useMobileMatches(customerId: number | null, enabled = true) {
  return useQuery<MobileMatchView>({
    queryKey: ["customer-mobile-matches", customerId],
    queryFn: () => customersApi.mobileMatches(customerId as number),
    enabled: enabled && customerId != null,
    staleTime: 300_000,
  });
}

export function howUsed(m: MobileMatch): string {
  if (!m.inBook) return "Used by a customer outside your book";
  const app = m.applicationId != null ? ` — application #${m.applicationId}` : "";
  switch (m.kind) {
    case "BORROWER": {
      const st = m.applicationStatus ? ` (${statusLabel(m.applicationStatus as ApplicationStatus)})` : "";
      return `Registered mobile of ${m.borrowerName ?? "a customer"}${app}${st}`;
    }
    case "BUREAU":
      return `Listed in ${m.borrowerName ?? "a customer"}'s credit bureau report${app}`;
    case "REFERENCE":
      return `Given as reference '${m.contactName ?? "—"}' (${m.relation ?? "—"}) by ${m.borrowerName ?? "a customer"} on application #${m.applicationId ?? "—"}`;
    case "LEAD":
      return `Lead '${m.leadName ?? "—"}' added by ${m.addedBy ?? "—"} via ${m.leadSource ?? "—"}${m.at ? ` on ${formatDate(m.at)}` : ""}`;
  }
}

const SOURCE_LABEL = { REGISTERED: "Registered", BUREAU: "Bureau report", REFERENCE: "Reference" } as const;

function sourceText(n: OurNumber, s: OurNumber["sources"][number]): string {
  return s === "REFERENCE" && n.referenceName ? `Reference '${n.referenceName}'` : SOURCE_LABEL[s];
}

function ourRole(n: OurNumber | undefined): string {
  return n ? n.sources.map((s) => sourceText(n, s)).join(", ") : "—";
}

// ---------------------------------------------------------------------------
// Bureau strip
// ---------------------------------------------------------------------------

export function BureauMobilesStrip({ customerId }: { customerId: number }) {
  const q = useMobileMatches(customerId);
  if (q.isLoading) return <Skeleton variant="line" rows={1} className="mb-3" />;
  if (q.error || !q.data) return <p className="mb-3 text-xs text-muted">Bureau mobiles unavailable.</p>;
  const b = q.data.bureau;
  return (
    <div className="mb-3 rounded border border-line bg-white p-3" data-testid="bureau-mobiles">
      <div className="mb-2 flex flex-wrap items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted">
        <Phone size={14} />
        <span>Mobiles in the bureau report</span>
        {b && (
          <span className="font-normal normal-case tracking-normal">
            {b.provider}
            {b.pulledAt ? ` · pulled ${formatDate(b.pulledAt)}` : ""}
          </span>
        )}
      </div>
      {!b ? (
        <p className="text-sm text-muted">No bureau report yet</p>
      ) : (
        <>
          {b.identityMismatch && (
            <p className="mb-2 flex items-center gap-1.5 rounded bg-warning-50 px-2 py-1 text-xs font-semibold text-warning-800">
              <AlertTriangle size={14} /> This report may belong to another person — numbers not used for matching
            </p>
          )}
          {b.numbers.length === 0 ? (
            <p className="text-sm text-muted">The report lists no phone numbers</p>
          ) : (
            <div className="flex flex-wrap gap-2">
              {b.numbers.map((n, i) => (
                <span
                  key={`${n.value}-${i}`}
                  data-kind={n.kind}
                  className={cn(
                    "inline-flex flex-wrap items-center gap-1.5 rounded border border-line px-2 py-1 text-xs",
                    n.kind === "MOBILE" ? "bg-white text-ink" : "bg-grey-100 text-ink/60",
                  )}
                >
                  <span className="font-mono">{n.value}</span>
                  <Badge variant="neutral" size="sm">{n.source === "CRIF" ? "CRIF" : "Experian"}</Badge>
                  {n.reportedDate && <span className="text-ink/60">{formatDate(n.reportedDate)}</span>}
                  {n.context && <span className="text-ink/60">{n.context}</span>}
                  {n.registered && <span className="rounded bg-navy px-2 py-0.5 text-[9px] font-bold uppercase tracking-wide text-white">Registered</span>}
                  {n.kind === "MASKED" && <span className="font-semibold">MASKED</span>}
                  {n.kind === "OTHER" && <span className="font-semibold">landline/other</span>}
                </span>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Reuse strip + drawer
// ---------------------------------------------------------------------------

export function MobileReuseStrip({ customerId, onOpen }: { customerId: number; onOpen: () => void }) {
  const q = useMobileMatches(customerId);
  const matches = q.data?.matches ?? [];
  const n = matches.length;
  if (q.isLoading || q.error || n === 0) return null;
  // Leads are not customers; out-of-book matches count as one more (their ids are redacted).
  const people = new Set(matches.filter((m) => m.inBook && m.kind !== "LEAD" && m.customerId != null).map((m) => m.customerId));
  const m = people.size + (matches.some((x) => !x.inBook) ? 1 : 0);
  return (
    <button
      type="button"
      onClick={onOpen}
      className="mt-2 flex w-full shrink-0 items-center gap-2 rounded border border-error-100 bg-error-50 px-3 py-1.5 text-left text-sm font-semibold text-error-700 hover:bg-error-100"
    >
      <PhoneCall size={15} />
      Mobile used in {n} other {n === 1 ? "case" : "cases"}
      {m > 1 && ` across ${m} customers`}
    </button>
  );
}

export function MobileMatchDrawer({
  customerId,
  open,
  onClose,
  onOpenApplication,
  focusIndex,
}: {
  customerId: number;
  open: boolean;
  onClose: () => void;
  /** Absent in a nested dialog — the card then links to the customer page instead. */
  onOpenApplication?: (id: number) => void;
  focusIndex?: number | null;
}) {
  const q = useMobileMatches(customerId, open);
  const focusRef = React.useRef<HTMLLIElement | null>(null);
  React.useEffect(() => {
    if (open && focusIndex != null) focusRef.current?.scrollIntoView?.({ block: "center" });
  }, [open, focusIndex, q.data]);
  const matches = q.data?.matches ?? [];
  const checked = q.data?.checked ?? [];
  return (
    <Drawer open={open} onClose={onClose} aria-label="Where these numbers appear">
      <DrawerHeader>
        <DrawerTitle>Where these numbers appear</DrawerTitle>
      </DrawerHeader>
      <DrawerBody>
        {q.isLoading ? (
          <Skeleton variant="line" rows={4} />
        ) : (
          <ul className="space-y-3">
            {matches.map((m, i) => (
              <li
                key={i}
                ref={i === focusIndex ? focusRef : undefined}
                className={cn("rounded border bg-white p-3 text-sm", i === focusIndex ? "border-navy" : "border-line")}
              >
                <p className="font-mono text-ink">{m.mobile}</p>
                <p className="text-xs text-ink/60">
                  This customer: {ourRole(checked.find((c) => c.mobile === m.mobile))}
                </p>
                <p className="mt-1 text-ink">{howUsed(m)}</p>
                {m.inBook && m.kind !== "LEAD" && m.at && <p className="text-xs text-ink/60">{formatDate(m.at)}</p>}
                {m.inBook && m.kind === "LEAD" ? (
                  <Link className="btn btn-sm btn-outline mt-2 inline-block" href={`/staff/leads?q=${m.mobile}`}>
                    Open lead
                  </Link>
                ) : m.inBook && m.applicationId != null && onOpenApplication ? (
                  <button
                    type="button"
                    className="btn btn-sm btn-outline mt-2"
                    onClick={() => {
                      onClose();
                      onOpenApplication(m.applicationId as number);
                    }}
                  >
                    Open case
                  </button>
                ) : m.inBook && m.customerId != null ? (
                  <Link className="btn btn-sm btn-outline mt-2 inline-block" href={customerPageHref(m.customerId)}>
                    Open customer
                  </Link>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </DrawerBody>
    </Drawer>
  );
}

// ---------------------------------------------------------------------------
// Dedupe-tab section
// ---------------------------------------------------------------------------

export function MobileMatchesSection({
  customerId,
  onOpenApplication,
}: {
  customerId: number;
  onOpenApplication?: (id: number) => void;
}) {
  const q = useMobileMatches(customerId);
  const [focus, setFocus] = React.useState<number | null>(null);
  const data = q.data;
  return (
    <Section title="Mobile numbers" icon={Phone} tone={data && data.matches.length > 0 ? "error" : "neutral"}>
      {q.isLoading ? (
        <Skeleton variant="line" rows={2} />
      ) : !data ? (
        <p className="text-sm text-muted">Mobile matches unavailable.</p>
      ) : (
        <div className="space-y-2">
          <ul className="flex flex-wrap gap-2">
            {data.checked.map((c) => (
              <li key={c.mobile} className="rounded border border-line px-2 py-1 text-xs">
                <span className="font-mono">{c.mobile}</span>{" "}
                {c.sources.map((s) => (
                  <Badge key={s} variant="neutral" size="sm" className="ml-1">
                    {sourceText(c, s)}
                  </Badge>
                ))}
              </li>
            ))}
          </ul>
          {data.matches.length === 0 ? (
            <p className="text-sm text-muted">None of these numbers appear on another case.</p>
          ) : (
            <ul className="divide-y divide-line text-sm">
              {data.matches.map((m, i) => (
                <li key={i}>
                  <button type="button" className="w-full py-1.5 text-left hover:bg-grey-100" onClick={() => setFocus(i)}>
                    <span className="font-mono">{m.mobile}</span> — {howUsed(m)}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      <MobileMatchDrawer
        customerId={customerId}
        open={focus != null}
        onClose={() => setFocus(null)}
        onOpenApplication={onOpenApplication}
        focusIndex={focus}
      />
    </Section>
  );
}
