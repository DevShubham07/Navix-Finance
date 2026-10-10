import type { Metadata } from "next";
import { PartnersPage } from "@/components/site/landing/partners";

export const metadata: Metadata = {
  title: 'Lending Partners: RBI-Registered NBFCs | DhanBoost',
  description: 'Loans by RBI-registered NBFC partners you can trust.',
  alternates: { canonical: '/partners' },
  // TEMPORARY noindex: this page lists placeholder RBI CoR numbers. Remove this line (and add
  // /partners back to sitemap.ts) once the real registrations are in place (Track B: B2).
  robots: { index: false, follow: true },
};

export default function Page() {
  return <PartnersPage />;
}
