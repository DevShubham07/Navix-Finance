import Link from "next/link";
import { AppHeader } from "@/components/app/app-header";
import { DemoBar } from "@/components/borrower/demo-bar";
import { BRAND } from "@/lib/brand";

/** Borrower app shell: slim header + content + compact footer. */
export default function BorrowerLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-ivory">
      <AppHeader />
      <main className="flex-1">{children}</main>
      <DemoBar />
      <footer className="border-t border-line bg-white">
        <div className="container flex flex-wrap items-center justify-between gap-3 py-5 text-sm text-muted">
          <span>© 2026 {BRAND.legalName}</span>
          <span className="flex gap-4">
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
