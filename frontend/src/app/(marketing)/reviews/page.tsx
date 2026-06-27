import type { Metadata } from "next";
import { html } from "../_content/reviews";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Reviews — Borrowers In Their Own Words — NAVIX',
  description: 'Where people talk about us — real borrower reviews.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
