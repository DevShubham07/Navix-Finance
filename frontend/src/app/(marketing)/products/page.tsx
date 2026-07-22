import type { Metadata } from "next";
import { html } from "../_content/products";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Loan Products — DhanBoost',
  description: 'Loan products built for real life: personal, salary advance, business and education.',
  alternates: { canonical: '/products' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
