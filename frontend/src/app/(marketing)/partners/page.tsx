import type { Metadata } from "next";
import { PartnersPage } from "@/components/site/landing/partners";

export const metadata: Metadata = {
  title: 'NBFC Partner — MLB Securities Private Limited — DhanBoost',
  description: 'Every DhanBoost loan is sanctioned and disbursed by MLB Securities Private Limited, an RBI-registered NBFC.',
  alternates: { canonical: '/partners' },
  // TEMPORARY noindex: this page lists placeholder RBI CoR numbers. Remove this line (and add
  // /partners back to sitemap.ts) once the real registrations are in place (Track B: B2).
  robots: { index: false, follow: true },
};

export default function Page() {
  return <PartnersPage />;
}
