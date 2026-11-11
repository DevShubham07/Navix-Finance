import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "eSign — DhanBoost",
  robots: { index: false, follow: false },
};

/** Public landing for external signers after Signzy redirects back. Not under /staff (middleware-gated). */
export default async function EsignDonePage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  const ok = (await searchParams).status === "ok";
  return (
    <section className="mx-auto flex min-h-[50vh] max-w-md items-center px-4 py-16">
      <div className="w-full rounded border border-line bg-white p-8 text-center shadow-sm">
        <h1 className="mb-2 font-serif text-xl font-semibold text-navy">
          {ok ? "Document signed" : "Signing didn't complete"}
        </h1>
        <p className="text-sm text-muted">
          {ok
            ? "Document signed — you can close this tab."
            : "Signing didn't complete — please ask the sender for a new link."}
        </p>
      </div>
    </section>
  );
}
