"use client";

import { PageHeader } from "@/components/staff/staff-ui";
import {
  useStaffMe,
  CreditQueuePanel,
  StatusQueue,
  ExecActions,
  HeadActions,
  PermissionGate,
  ROLE_LABEL,
} from "@/components/staff/live-pipeline";

/**
 * Credit queue (live). Credit Head assigns KYC-approved applications to an
 * executive and makes the final approval; Credit Executive reviews/recommends.
 * Each role sees only its own panels (PermissionGate); the backend still enforces
 * role + separation of duties on every action as the source of truth.
 */
export default function CreditQueuePage() {
  const me = useStaffMe();
  const role = me.data?.role;

  return (
    <div>
      <PageHeader title="Credit queue" subtitle="Assign applications, review, and approve — maker-checker with SoD enforced.">
        {role && <span className="rounded-full bg-navy-tint px-3 py-1 text-sm font-semibold text-navy">{ROLE_LABEL[role]}</span>}
      </PageHeader>

      <div className="space-y-8">
        {/* Credit Head only: assign applications to an executive + final approval. */}
        <PermissionGate permission="loan:approve">
          <CreditQueuePanel />
        </PermissionGate>
        {/* Credit Executive only: review applications assigned to them. */}
        <PermissionGate permission="loan:review">
          <StatusQueue title="Credit executive review" status="CREDIT_EXEC_PENDING" actions={(app) => <ExecActions app={app} />} />
        </PermissionGate>
        {/* Credit Head only: final approval (SoD vs the recommending executive). */}
        <PermissionGate permission="loan:approve">
          <StatusQueue title="Credit head decision" status="CREDIT_HEAD_PENDING" actions={(app) => <HeadActions app={app} />} />
        </PermissionGate>
      </div>
    </div>
  );
}
