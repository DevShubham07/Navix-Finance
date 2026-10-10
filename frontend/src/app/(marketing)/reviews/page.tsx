import type { Metadata } from "next";
import { ReviewsPage } from "@/components/site/landing/reviews";

export const metadata: Metadata = {
  title: 'Reviews: Borrowers In Their Own Words | DhanBoost',
  description: 'Where people talk about us: real borrower reviews.',
  alternates: { canonical: '/reviews' },
  // TEMPORARY noindex: the aggregate review stats here are unverified. Remove this line (and add
  // /reviews back to sitemap.ts) once the numbers are substantiated or replaced (Track B: B4).
  robots: { index: false, follow: true },
};

export default function Page() {
  return <ReviewsPage />;
}
