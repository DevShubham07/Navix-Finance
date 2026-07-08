"use client";

/**
 * Open any application by ID to load its customer review — gated to reviewer roles
 * (not collections/dev). Moved verbatim from the former `live-pipeline.tsx` god-file.
 */

import * as React from "react";
import { Input } from "@/components/ui";
import { REVIEW_PERMS } from "@/components/staff/pipeline/hooks";
import { PermissionGate } from "@/components/staff/pipeline/actions";
import { CustomerReview } from "@/components/staff/pipeline/customer-review";

export function ReviewLookup() {
  const [input, setInput] = React.useState("");
  const [openId, setOpenId] = React.useState<number | null>(null);
  return (
    <PermissionGate permission={REVIEW_PERMS} fallback={null}>
    <section className="rounded border border-line bg-white shadow-sm">
      <header className="border-b border-line px-5 py-3">
        <h2 className="font-serif text-lg font-semibold text-navy">Review an application</h2>
        <p className="mt-0.5 text-xs text-muted">
          Open any application by its ID to view the customer&apos;s details and documents — loaded on demand.
        </p>
      </header>
      <div className="flex flex-wrap items-end gap-2 px-5 py-4">
        <Input
          label="Application ID"
          value={input}
          onChange={(e) => setInput(e.target.value.replace(/\D/g, ""))}
          placeholder="e.g. 8"
          className="!mb-0"
          inputClassName="w-32"
        />
        <button
          onClick={() => setOpenId(input ? Number.parseInt(input, 10) : null)}
          disabled={!input}
          className="btn btn-sm btn-navy disabled:opacity-50"
        >
          Open review
        </button>
      </div>
      {openId != null && (
        <div className="border-t border-line px-5 py-4">
          <div className="mb-2 text-sm font-semibold text-navy">Application #{openId}</div>
          <CustomerReview key={openId} applicationId={openId} />
        </div>
      )}
    </section>
    </PermissionGate>
  );
}
