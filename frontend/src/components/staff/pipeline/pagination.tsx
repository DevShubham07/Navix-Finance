"use client";

import * as React from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";

export interface UsePaginationResult<T> {
  page: number;
  setPage: (page: number) => void;
  pageSize: number;
  setPageSize: (size: number) => void;
  pageCount: number;
  pageRows: T[];
  total: number;
}

/**
 * Simple client-side pagination over an in-memory row array. 1-indexed `page`.
 * Clamps `page` back into range whenever `rows.length` or `pageSize` changes
 * in a way that makes the current page invalid.
 */
export function usePagination<T>(rows: T[], initialPageSize = 25): UsePaginationResult<T> {
  const [page, setPage] = React.useState(1);
  const [pageSize, setPageSize] = React.useState(initialPageSize);

  const total = rows.length;
  const pageCount = Math.max(1, Math.ceil(total / pageSize));

  React.useEffect(() => {
    setPage((p) => Math.min(Math.max(1, p), pageCount));
  }, [pageCount]);

  const pageRows = React.useMemo(() => {
    const start = (page - 1) * pageSize;
    return rows.slice(start, start + pageSize);
  }, [rows, page, pageSize]);

  return {
    page,
    setPage,
    pageSize,
    setPageSize: (size: number) => {
      setPageSize(size);
      setPage(1);
    },
    pageCount,
    pageRows,
    total,
  };
}

export interface PaginationBarProps {
  page: number;
  pageCount: number;
  setPage: (page: number) => void;
  total: number;
  pageSize: number;
  setPageSize: (size: number) => void;
  pageSizeOptions?: number[];
}

export function PaginationBar({
  page,
  pageCount,
  setPage,
  total,
  pageSize,
  setPageSize,
  pageSizeOptions = [25, 50, 100],
}: PaginationBarProps) {
  if (total === 0) return null;

  const start = (page - 1) * pageSize + 1;
  const end = Math.min(page * pageSize, total);

  return (
    <div className="flex flex-wrap items-center justify-between gap-2 border-t border-line bg-white px-3 py-2 text-xs text-muted">
      <span>
        Showing {start}–{end} of {total}
      </span>
      <div className="flex items-center gap-3">
        <label className="flex items-center gap-1.5">
          Rows per page
          <select
            aria-label="Rows per page"
            value={pageSize}
            onChange={(e) => setPageSize(Number(e.target.value))}
            className="rounded border border-line px-1.5 py-1 text-xs text-ink"
          >
            {pageSizeOptions.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => setPage(Math.max(1, page - 1))}
            disabled={page <= 1}
            className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
          >
            <ChevronLeft size={13} /> Prev
          </button>
          <span className="px-1 text-xs text-muted">
            Page {page} of {pageCount}
          </span>
          <button
            type="button"
            onClick={() => setPage(Math.min(pageCount, page + 1))}
            disabled={page >= pageCount}
            className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
          >
            Next <ChevronRight size={13} />
          </button>
        </div>
      </div>
    </div>
  );
}
