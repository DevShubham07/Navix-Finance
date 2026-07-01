import type { Metadata } from "next";
import { html } from "../_content/how-it-works";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'How It Works — NAVIX',
  description: 'From application to your account in four simple steps — fully digital.',
  alternates: { canonical: '/how-it-works' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
