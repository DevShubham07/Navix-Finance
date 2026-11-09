"use client";

import Link from "next/link";

/** Shown when the admin close-applications switch refuses a new application or reloan (APPLICATIONS_CLOSED). */
export default function ApplicationsClosedPage() {
  return (
    <div className="container max-w-content py-12">
      <h1 className="font-serif text-3xl leading-tight text-navy">
        We&apos;re not accepting new applications right now
      </h1>
      <p className="mt-4 text-muted">This includes new loans and reloans. Please check back soon.</p>
      <p className="mt-3 text-muted">
        If you already have a loan or an application in progress, nothing has changed. You can still track it and make
        repayments.
      </p>
      <div className="mt-8 flex flex-col gap-2 sm:flex-row">
        <Link href="/dashboard" className="btn btn-navy">Go to my account</Link>
        <Link href="/support" className="btn btn-outline">Contact support</Link>
      </div>
    </div>
  );
}
