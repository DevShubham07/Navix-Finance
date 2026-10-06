/**
 * Progress and per-loan summary text for the collections register's bulk officer assign
 * (`BulkAssignOfficerDialog` in `components/staff/collections-assign.tsx`).
 */

export type LoanAssignOutcome = { loanId: number; ok: true } | { loanId: number; ok: false; message: string };

/**
 * One outcome per loan, in the order the loans were submitted (the runner reports successes and
 * failures as two lists). A loan missing from both lists — which the runner never produces — is
 * reported as a failure rather than silently dropped or claimed as assigned.
 */
export function perLoanAssignOutcomes(
  loanIds: readonly number[],
  result: { ok: readonly number[]; failed: ReadonlyArray<{ id: number; message: string }> },
): LoanAssignOutcome[] {
  const ok = new Set(result.ok);
  const failed = new Map(result.failed.map((f) => [f.id, f.message]));
  return loanIds.map((loanId) => {
    if (ok.has(loanId)) return { loanId, ok: true };
    return { loanId, ok: false, message: failed.get(loanId) ?? "No result recorded" };
  });
}

/** "12 / 40 assigned" while the run is in flight, with ", 1 failed" once anything has failed. */
export function bulkAssignProgressLabel(progress: { ok: number; failed: number; total: number }): string {
  const base = `${progress.ok} / ${progress.total} assigned`;
  return progress.failed > 0 ? `${base}, ${progress.failed} failed` : base;
}
