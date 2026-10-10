import * as React from "react";
import Link from "next/link";
import { ArrowRight, Mail, MessagesSquare, Phone } from "lucide-react";
import { BRAND } from "@/lib/brand";
import { COMMUNICATION_CONSENT_TEXT, COMMUNICATION_PRIVACY_TEXT } from "@/lib/communication-consent";
import { CtaBand, PageHero } from "./page-kit";
import { Container, FloatTile, IconTile, Section, SectionHead } from "./primitives";

/** /help in the landing layout language. Copy: formerly `(marketing)/_content/support.ts`. */
const CHANNELS = [
  { icon: MessagesSquare, title: "Live chat", text: "Chat with our team for quick answers, Mon–Sat, 9:30 AM–6:30 PM.", cta: "Start a chat" },
  { icon: Mail, title: "Email us", text: "Write to us anytime. We reply within one business day.", cta: BRAND.email },
  { icon: Phone, title: "Call us", text: "Prefer talking? Our support line is open six days a week.", cta: BRAND.phone },
];

const TOPICS = [
  { href: "/faq", title: "Applications & eligibility", text: "Who can apply, what you need, and how the decision works." },
  { href: "/calculator", title: "Rates, fees & charges", text: "Understand interest, APR and our no-advance-fee promise." },
  { href: "/faq", title: "Repayments", text: "How to pay, payment methods, and early repayment." },
  { href: "/grievance", title: "Grievance redressal", text: "Not happy with something? Here's how to escalate." },
];

export function HelpPage() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "Support" }]}
        label="Support"
        lead="We're here"
        muted="to help"
        sub="Questions about your application, repayment or account? Reach us your way, six days a week."
        floats={
          <>
            <FloatTile className="left-[7%] top-[24%]" rotate={-8}>
              <IconTile size={78} className="text-gold-dark">
                <MessagesSquare size={32} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
            <FloatTile className="right-[8%] top-[28%]" rotate={7} delay={2}>
              <IconTile size={68} className="text-ink">
                <Phone size={28} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
          </>
        }
      />
      <Section labelledBy="channels-title" className="pb-10">
        <Container>
          <h2 id="channels-title" className="sr-only">
            Ways to reach us
          </h2>
          <ul className="m-0 grid list-none gap-5 p-0 md:grid-cols-3">
            {CHANNELS.map(({ icon: Icon, title, text, cta }, i) => (
              <li key={title} className={`reveal ${i ? `d${i}` : ""} lp-card flex flex-col rounded-[24px] p-7`}>
                <IconTile size={52} className="text-gold-dark">
                  <Icon aria-hidden size={22} strokeWidth={1.8} />
                </IconTile>
                <h3 className="lp-h3 mt-6">{title}</h3>
                <p className="m-0 mt-2 flex-1 text-[14.5px] leading-[1.6] text-slate">{text}</p>
                <Link
                  href="/contact"
                  className="mt-6 inline-flex h-10 w-fit max-w-full items-center truncate rounded-full border border-[rgb(var(--c-line-2))] bg-paper px-4 text-[14px] font-semibold text-ink shadow-pill hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark"
                >
                  {cta}
                </Link>
              </li>
            ))}
          </ul>
          <p className="m-0 mt-8 max-w-[760px] text-[13px] leading-[1.6] text-slate">
            {COMMUNICATION_CONSENT_TEXT}
            <br />
            {COMMUNICATION_PRIVACY_TEXT}
          </p>
        </Container>
      </Section>
      <Section labelledBy="topics-title" className="pt-10">
        <Container>
          <SectionHead label="Browse help topics" id="topics-title" lead="Popular questions" />
          <ul className="m-0 mt-12 grid list-none gap-4 p-0 md:grid-cols-2">
            {TOPICS.map((t) => (
              <li key={t.title} className="reveal">
                <Link
                  href={t.href}
                  className="group lp-card flex h-full items-start justify-between gap-4 rounded-[22px] p-6 text-ink transition-transform duration-300 hover:-translate-y-0.5 hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark"
                >
                  <span>
                    <span className="lp-h3 block">{t.title}</span>
                    <span className="mt-1.5 block text-[14.5px] leading-[1.6] text-slate">{t.text}</span>
                  </span>
                  <ArrowRight
                    aria-hidden
                    size={18}
                    className="mt-1 shrink-0 text-muted transition-transform group-hover:translate-x-0.5 group-hover:text-gold-dark"
                  />
                </Link>
              </li>
            ))}
          </ul>
        </Container>
      </Section>
      <CtaBand
        id="help-cta"
        lead="Still need a hand?"
        sub="Our team is friendly, fast and actually human. Reach out and we'll sort it."
        primary={{ href: "/contact", label: "Contact support" }}
        secondary={{ href: "/faq", label: "Read FAQs" }}
      />
    </div>
  );
}
