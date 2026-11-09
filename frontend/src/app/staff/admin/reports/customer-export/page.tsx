"use client";

import Link from "next/link";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice, useCan, useStaffMe } from "@/components/staff/live-pipeline";
import { CustomerExportPanel } from "@/components/staff/customer-export-panel";

/**
 * Admin · Reports · Customer data export. ADMIN only: the server checks the real role on every call,
 * this page only keeps everyone else from seeing a screen that would fail.
 */
export default function CustomerExportPage() {
  const me = useStaffMe().data;
  const can = useCan();
  if (me && !can("staff:manage")) return <NoAccessNotice message="Admin access only." />;
  return (
    <div>
      <Link href="/staff/admin/reports?seg=customers" className="text-sm text-muted hover:text-ink">
        Reports / Customers
      </Link>
      <PageHeader
        title="Customer data export"
        subtitle="Choose who to include and which fields you want, then download a CSV or a PDF."
      />
      <CustomerExportPanel />
    </div>
  );
}
