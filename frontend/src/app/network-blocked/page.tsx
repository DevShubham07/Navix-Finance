import type { Metadata } from "next";
import { ShieldAlert } from "lucide-react";

/**
 * Shown by `middleware.ts` (as a 403 rewrite, so the address bar keeps the page they asked for)
 * when the network guard refuses a visitor: VPN, proxy, Tor / private relay, or an IP outside
 * India. Deliberately static and outside every route group — it must render without the BFF,
 * a session, or anything else the guard is standing in front of. Excluded from the matcher.
 */
export const metadata: Metadata = {
  title: "Access blocked — DhanBoost",
  robots: { index: false, follow: false },
};

const COPY: Record<string, { title: string; body: string }> = {
  country: {
    title: "DhanBoost is available only in India",
    body: "We could not confirm that you are connecting from India. If you are in India, switch off any VPN, proxy or private relay and reload this page.",
  },
  vpn: {
    title: "Please turn off your VPN",
    body: "For your security, DhanBoost can't be used over a VPN. Disconnect it and reload this page.",
  },
  proxy: {
    title: "Please turn off your proxy",
    body: "For your security, DhanBoost can't be used through a proxy or private relay (such as iCloud Private Relay). Switch it off and reload this page.",
  },
  tor: {
    title: "Tor is not supported",
    body: "For your security, DhanBoost can't be used over the Tor network. Open it in a regular browser and reload this page.",
  },
};

export default async function NetworkBlockedPage({
  searchParams,
}: {
  searchParams: Promise<{ reason?: string }>;
}) {
  const { reason } = await searchParams;
  const copy = COPY[reason ?? ""] ?? COPY.proxy;

  return (
    <main className="min-h-screen bg-ivory">
      <div className="container max-w-content py-16">
        <div className="form-card text-center">
          <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-error-50 text-error-600">
            <ShieldAlert size={34} />
          </span>
          <h1 className="font-serif text-2xl text-navy">{copy.title}</h1>
          <p className="mt-3 text-sm text-muted">{copy.body}</p>
          <p className="mt-6 text-xs text-muted">
            Still seeing this? Write to us at{" "}
            <a className="underline" href="mailto:support@dhanboost.com">
              support@dhanboost.com
            </a>
            .
          </p>
        </div>
      </div>
    </main>
  );
}
