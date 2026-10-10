import type { Metadata } from "next";
import { html } from "../_content/calculator";
import { MarketingHtml } from "@/components/site/marketing-html";
import { withoutHero } from "@/components/site/landing/legacy-html";
import { CalculatorHero } from "@/components/site/landing/calculator-hero";

export const metadata: Metadata = {
  title: 'Loan Calculator & Transparent Rates | DhanBoost',
  description: 'See your exact repayment before you borrow. Transparent, upfront, fair pricing.',
  alternates: { canonical: '/calculator' },
};

/**
 * The calculator keeps its existing interactive widgets (amount/tenure sliders, repayment
 * calendar, rate table — wired by MarketingScripts) under a hero in the landing layout language.
 */
export default function Page() {
  return (
    <>
      <CalculatorHero />
      <MarketingHtml html={withoutHero(html)} />
    </>
  );
}
