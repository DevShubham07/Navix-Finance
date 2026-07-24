/**
 * FAQPage JSON-LD for /faq, mirroring the 11 Q&As in _content/faq.ts.
 *
 * NOTE: Google retired FAQ rich results for all sites (May 2026), so this earns NO Google SERP
 * feature. Its value is AI-citation / entity grounding (AI Overviews, ChatGPT, Perplexity) — a
 * machine-readable Q&A pairing of content that otherwise only lives in accordion DOM. Keep this in
 * sync with faq.ts if the questions change.
 */
const FAQ: { q: string; a: string }[] = [
  {
    q: "Is DhanBoost a lender?",
    a: "No. DhanBoost is a digital lending platform. All loans are sanctioned and disbursed by our RBI-registered NBFC partners, who are the lender of record and remain regulated by the RBI.",
  },
  {
    q: "How quickly can I get a loan?",
    a: "Eligibility decisions are often instant. Once your KYC is complete and you e-sign the agreement, funds are typically disbursed by the partner NBFC within 24–48 hours.",
  },
  {
    q: "Will applying affect my credit score?",
    a: "Checking eligibility on DhanBoost does not impact your credit score. A formal credit enquiry only happens if you proceed and accept a loan offer from the partner NBFC.",
  },
  {
    q: "What documents do I need?",
    a: "Typically your PAN, Aadhaar (for e-KYC) and bank account details. Everything is verified digitally — no physical paperwork or branch visits.",
  },
  {
    q: "Who is eligible to apply?",
    a: "Indian citizens aged 21–58, salaried with a net monthly income of at least ₹40,000, with valid PAN & Aadhaar and an active bank account. Final eligibility is set by the partner NBFC.",
  },
  {
    q: "How much can I borrow?",
    a: "Between ₹5,000 and ₹10,00,000, for a tenure of 7 to 40 days. Your approved limit depends on the NBFC's assessment of your profile.",
  },
  {
    q: "Are there any hidden charges or advance fees?",
    a: "Never. DhanBoost does not charge any advance or upfront fee. All applicable interest and charges are disclosed in the Key Fact Statement before you accept the offer.",
  },
  {
    q: "Can I repay my loan early?",
    a: "Yes — and there are no pre-closure or prepayment charges. Repaying early reduces the total interest you pay.",
  },
  {
    q: "What happens if I miss a payment?",
    a: "Late payments attract a late-payment fee of 2% per day on the overdue principal (capped at 30 days), as set out in your Key Fact Statement, and can affect your credit score. If you're struggling, contact us early — we'll help you find a way forward respectfully.",
  },
  {
    q: "Is my data safe with DhanBoost?",
    a: "Yes. We use 256-bit encryption and follow ISO-27001-aligned controls. We never sell your data and only share what's necessary with the partner NBFC to process your loan.",
  },
  {
    q: "How do I spot a fake DhanBoost app or agent?",
    a: "DhanBoost never asks for advance fees or upfront payments. We only use official channels (dhanboost.com and info@dhanboost.com). Report anything suspicious to info@dhanboost.com.",
  },
];

export function FaqSchema() {
  const graph = {
    "@context": "https://schema.org",
    "@type": "FAQPage",
    mainEntity: FAQ.map(({ q, a }) => ({
      "@type": "Question",
      name: q,
      acceptedAnswer: { "@type": "Answer", text: a },
    })),
  };
  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{ __html: JSON.stringify(graph) }}
    />
  );
}
