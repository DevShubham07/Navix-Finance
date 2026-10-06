"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { XCircle } from "lucide-react";
import { clearBorrowerClientState } from "@/lib/api/live-journey";

/**
 * Terminal screen for an engine rejection that fires mid-journey (V75 Aadhaar mismatch). Deliberately
 * outside the signup layout: there is no step to count, and the progress bar would be a lie. The
 * reason is never shown — it lives in the ADMIN rejection register — only the outcome. Client-side
 * onboarding state is cleared so a reload cannot drop the borrower back into a dead application.
 */
export default function ApplicationRejectedPage() {
  const router = useRouter();
  React.useEffect(() => {
    clearBorrowerClientState();
  }, []);

  return (
    <div className="bg-ivory">
      <div className="container max-w-content py-16">
        <div className="form-card text-center">
          <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-error-50 text-error-600">
            <XCircle size={34} />
          </span>
          <h1 className="font-serif text-2xl text-navy">Your application has been rejected</h1>
          <p className="mt-3 text-sm text-muted">
            Thank you for your interest in DhanBoost. We&apos;re unable to take your application
            forward. If you believe this is a mistake, please contact our support team.
          </p>
          <div className="mt-6 flex flex-col gap-2 sm:flex-row sm:justify-center">
            <button type="button" onClick={() => router.replace("/support")} className="btn btn-outline">
              Contact support
            </button>
            <button type="button" onClick={() => router.replace("/")} className="btn btn-gold">
              Back to home
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
