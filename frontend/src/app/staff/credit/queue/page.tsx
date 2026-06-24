"use client";

import { PageHeader } from "@/components/staff/staff-ui";
import {
  useStaffMe,
  CreditQueuePanel,
  StatusQueue,
  ExecActions,
  HeadActions,
  ROLE_LABEL,
} from "@/components/staff/live-pipeline";

/**
 * Credit queue (live). Credit Head assigns KYC-approved applications to an
 * executive and makes the final approval; Credit Executive reviews/recommends.
 * Actions are role-enforced server-side, so all panels are shown and the backend
 * rejects an action the signed-in role isn't allowed to take (separation of duties).
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
        {/* Credit Head: assign KYC-approved + applied applications to an executive. */}
        <CreditQueuePanel />
        {/* Credit Executive: review applications assigned to them. */}
        <StatusQueue title="Credit executive review" status="CREDIT_EXEC_PENDING" actions={(app) => <ExecActions app={app} />} />
        {/* Credit Head: final approval (separation of duties vs the recommending executive). */}
        <StatusQueue title="Credit head decision" status="CREDIT_HEAD_PENDING" actions={(app) => <HeadActions app={app} />} />
      </div>
    </div>
  );
}
