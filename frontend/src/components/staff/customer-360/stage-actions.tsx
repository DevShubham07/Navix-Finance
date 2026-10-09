"use client";

import * as React from "react";
import type { ApplicationView } from "@/lib/api/applications";
import {
  AssignActions,
  CreditDecisionActions,
  DisbursementActions,
  AdminForceDisbursementAction,
  SanctionedRejectAction,
} from "@/components/staff/live-pipeline";

/** The maker-checker action available for an application's current pipeline stage. */
export function stageActionFor(app: ApplicationView): React.ReactNode {
  switch (app.status) {
    case "KYC_PENDING":
    case "KYC_APPROVED":
      // V45: a submitted intake is assigned by the Credit Head, not KYC-approved first.
      return <AssignActions app={app} />;
    case "CREDIT_EXEC_PENDING":
      return <CreditDecisionActions app={app} />;
    case "SANCTIONED":
      // "Reject with a reason" sits beside the ADMIN-only "force with disbursement" escape hatch —
      // normally this stage just waits on the borrower's own offer journey (accept-offer), so every
      // other role sees "Not your step" on the force button (SanctionedRejectAction is credit-role
      // gated separately, via loan:review).
      return (
        <>
          <SanctionedRejectAction app={app} />
          <AdminForceDisbursementAction app={app} />
        </>
      );
    case "DISBURSEMENT_PENDING":
    case "DISBURSEMENT_FAILED":
      return <DisbursementActions app={app} />;
    // ACCOUNTANT_PENDING deliberately has no action: the hop was retired in V48 and V48's migration
    // moved every live row out of it. A historical row opens read-only.
    default:
      return null;
  }
}

/** Which lifecycle tab hosts the action for this status (drives the header's "Action pending" chip). */
export function stageActionTab(status: ApplicationView["status"]): "sanction" | "disbursal" | null {
  switch (status) {
    case "KYC_PENDING":
    case "KYC_APPROVED":
    case "CREDIT_EXEC_PENDING":
    case "SANCTIONED":
      return "sanction";
    case "DISBURSEMENT_PENDING":
    case "DISBURSEMENT_FAILED":
      return "disbursal";
    default:
      return null;
  }
}
