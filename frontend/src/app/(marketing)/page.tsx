import type { Metadata } from "next";
import { HomePage } from "@/components/site/landing/home";

export const metadata: Metadata = {
  title: 'DhanBoost — Instant Personal Loans, Fully Digital',
  description: 'Instant personal loans ₹5,000–₹10,00,000, fully online, fairly priced — salary-linked, with a single repayment and no advance fees.',
  alternates: { canonical: '/' },
};

export default function Page() {
  return <HomePage />;
}
