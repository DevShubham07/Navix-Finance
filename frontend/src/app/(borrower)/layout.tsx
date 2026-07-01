import type { Metadata } from "next";
import Link from "next/link";
import { Linkedin } from "lucide-react";
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
          <span>
            © 2026 {BRAND.legalName} · Built by{" "}
            <a href="https://softsolutionsai.com" target="_blank" rel="noopener noreferrer" className="hover:text-navy">softsolutionsai.com</a>
          </span>
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <Link href="/policies#privacy" className="hover:text-navy">Privacy</Link>
            <Link href="/policies#terms" className="hover:text-navy">Terms</Link>
            <Link href="/grievance" className="hover:text-navy">Grievance</Link>
            <Link href="/" className="hover:text-navy">Main site</Link>
            <a href="https://www.linkedin.com/company/softsolutionsai/" target="_blank" rel="noopener noreferrer" aria-label="SoftSolutionsAI on LinkedIn" className="inline-flex items-center hover:text-navy">
              <Linkedin size={16} />
            </a>
          </span>
        </div>
      </footer>
    </div>
  );
}
