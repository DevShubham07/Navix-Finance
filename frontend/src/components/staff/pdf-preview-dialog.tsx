"use client";

import { Dialog } from "@/components/ui/dialog";

/**
 * In-app PDF viewer popup — replaces opening a presigned document URL in a new browser tab. Renders
 * the PDF inline via `<object>` (same pattern as the borrower sanction-letter page); falls back to a
 * plain link when the browser can't render PDFs inline (e.g. some mobile browsers).
 */
export function PdfPreviewDialog({
  open,
  onClose,
  url,
  title,
}: {
  open: boolean;
  onClose: () => void;
  url: string | null;
  title?: string;
}) {
  return (
    <Dialog
      open={open && !!url}
      onClose={onClose}
      className="!max-w-[80vw] !w-[80vw]"
      aria-label={title ?? "PDF preview"}
    >
      {url && (
        <>
          <object data={url} type="application/pdf" className="hidden h-[80vh] w-full sm:block">
            <p className="p-4 text-sm text-muted">
              Preview unavailable.{" "}
              <a href={url} target="_blank" rel="noopener noreferrer" className="font-semibold text-navy underline">
                Open PDF
              </a>
            </p>
          </object>
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            className="block p-4 text-sm font-semibold text-navy underline sm:hidden"
          >
            Open PDF
          </a>
        </>
      )}
    </Dialog>
  );
}
