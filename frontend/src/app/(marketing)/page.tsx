import type { Metadata } from "next";
import { HomePage } from "@/components/site/landing/home";

export const metadata: Metadata = {
  title: 'DhanBoost | Instant Personal Loans, Fully Digital',
  description: 'Salary-linked personal loans from ₹1,000, fully online and fairly priced, with one repayment on your salary day and no advance fees.',
  alternates: { canonical: '/' },
};

export default function Page() {
  return <HomePage />;
}
