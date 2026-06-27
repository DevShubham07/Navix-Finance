"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

/** Bare /signup → the first wizard step (mobile + OTP, which establishes the session). */
export default function SignupIndexRedirect() {
  const router = useRouter();
  React.useEffect(() => {
    router.replace("/signup/mobile-otp");
  }, [router]);
  return (
    <div className="py-16 text-center text-muted">
      <Loader2 size={24} className="mx-auto mb-2 animate-spin" /> Starting your application…
    </div>
  );
}
