"use client";

/** Previous / next for the console's server-paged lists. */
export function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (p: number) => void }) {
  if (total <= size) return null;
  const last = Math.max(0, Math.ceil(total / size) - 1);
  return (
    <div className="flex items-center justify-end gap-2 pt-2 text-xs text-muted">
      <span>
        {page * size + 1}–{Math.min(total, (page + 1) * size)} of {total}
      </span>
      <button type="button" className="btn btn-sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Previous
      </button>
      <button type="button" className="btn btn-sm" disabled={page >= last} onClick={() => onPage(page + 1)}>
        Next
      </button>
    </div>
  );
}
