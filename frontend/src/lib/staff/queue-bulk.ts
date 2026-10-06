/**
 * Pure helpers behind the live-applications bulk actions (`components/staff/pipeline/bulk-actions.tsx`).
 *
 * There is deliberately no batch endpoint: a bulk run is N calls to the existing per-id endpoint, so
 * every audited SoD/ownership/event-trail guard those endpoints enforce still runs once per id. This
 * module only decides how many of those calls are in flight at once and how their outcomes are
 * reported — it never changes which endpoint is called or for which ids.
 */

/** Outcome of a bulk run, in INPUT order (not completion order), so a summary reads predictably. */
export interface BulkRunResult<Id> {
  ok: Id[];
  failed: { id: Id; message: string }[];
}

export interface BulkRunOptions {
  /** Maximum calls in flight at once. Clamped to at least 1. Default 4. */
  concurrency?: number;
  /** Called after each id settles (success or failure) with the running count. */
  onProgress?: (done: number, total: number) => void;
  /** Turns a thrown value into the message stored on a failed id. */
  describeError?: (e: unknown) => string;
}

export const DEFAULT_BULK_CONCURRENCY = 4;

function defaultDescribe(e: unknown): string {
  if (e instanceof Error) return e.message;
  return typeof e === "string" ? e : "Request failed";
}

/**
 * Run `call` once per id with at most `concurrency` calls in flight. Each id gets its own try/catch,
 * so a failure is recorded against that id and never aborts the rest. Resolves once every id has
 * settled; never rejects.
 */
export async function runWithConcurrency<Id>(
  ids: readonly Id[],
  call: (id: Id) => Promise<unknown>,
  { concurrency = DEFAULT_BULK_CONCURRENCY, onProgress, describeError = defaultDescribe }: BulkRunOptions = {},
): Promise<BulkRunResult<Id>> {
  const total = ids.length;
  const outcomes: ({ ok: true } | { ok: false; message: string })[] = new Array(total);
  let next = 0;
  let done = 0;

  const worker = async () => {
    while (next < total) {
      const index = next++;
      try {
        await call(ids[index]);
        outcomes[index] = { ok: true };
      } catch (e) {
        // A describeError that itself throws must not take the worker (and its remaining ids) down.
        let message: string;
        try {
          message = describeError(e);
        } catch {
          message = defaultDescribe(e);
        }
        outcomes[index] = { ok: false, message };
      }
      done += 1;
      // Progress is reporting only: a throwing callback must not stop the run or reject it.
      try {
        onProgress?.(done, total);
      } catch {
        /* ignored */
      }
    }
  };

  const width = Math.max(1, Math.min(Math.floor(concurrency) || 1, total));
  await Promise.all(Array.from({ length: total === 0 ? 0 : width }, worker));

  const result: BulkRunResult<Id> = { ok: [], failed: [] };
  outcomes.forEach((o, i) => {
    if (o.ok) result.ok.push(ids[i]);
    else result.failed.push({ id: ids[i], message: o.message });
  });
  return result;
}

/**
 * The one-line hint shown in a queue header while bulk selection is available but nothing is
 * ticked. Names only the bulk actions this queue actually offers the signed-in role — a disbursement
 * queue has no bulk assign, so it must not say "assign". `null` when neither is offered.
 */
export function bulkSelectionHint({ canAssign, canReject }: { canAssign: boolean; canReject: boolean }): string | null {
  if (canAssign && canReject) return "Tick rows to assign or reject in bulk";
  if (canAssign) return "Tick rows to assign in bulk";
  if (canReject) return "Tick rows to reject in bulk";
  return null;
}
