"use client";

import * as React from "react";
import { useParams, useRouter } from "next/navigation";

/**
 * The case workspace was folded into the one customer pop-up (opened on its Collections tab). This
 * route stays so old links and notifications keep working: it forwards to the worklist, which
 * opens the pop-up for `?open=<loanId>`. A pre-loan-id link carrying a case UUID has no loan to
 * open, so it lands on the plain worklist.
 */
export default function CollectionsCaseRedirect() {
  const { loanId } = useParams<{ loanId: string }>();
  const router = useRouter();
  React.useEffect(() => {
    router.replace(/^\d+$/.test(loanId) ? `/staff/collections?open=${loanId}&tab=collections` : "/staff/collections");
  }, [loanId, router]);
  return <div className="h-40 animate-pulse rounded bg-grey-100" />;
}
