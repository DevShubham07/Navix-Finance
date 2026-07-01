import type { Metadata } from "next";
import Link from "next/link";
import { AppHeader } from "@/components/app/app-header";
import { BRAND } from "@/lib/brand";

// The borrower app + its public auth-entry pages (login/signup/forgot/reset) are not search
// content — keep them out of the index (crawlable so the noindex is seen; see robots.ts).
export const metadata: Metadata = { robots: { index: false, follow: false } };

/** Borrower app shell: slim header + content + compact footer. */
export default function BorrowerLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-ivory">
      <AppHeader />
      <main className="flex-1">{children}</main>
      <footer className="shrink-0 border-t border-line bg-white py-5">
        <div className="container flex flex-wrap items-center justify-between gap-3 text-sm text-muted">
          <span>© 2026 {BRAND.legalName}</span>
          <span className="flex flex-wrap gap-x-4 gap-y-1">
            <Link href="/policies#privacy" className="hover:text-navy">Privacy</Link>
            <Link href="/policies#terms" className="hover:text-navy">Terms</Link>
            <Link href="/grievance" className="hover:text-navy">Grievance</Link>
            <Link href="/" className="hover:text-navy">Main site</Link>
          </span>
        </div>
      </footer>
    </div>
  );
}
