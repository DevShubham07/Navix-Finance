import * as React from "react";
import Link from "next/link";
import { Ellipsis, Facebook, Instagram, MessageSquareQuote, Search } from "lucide-react";
import { PageHero } from "./page-kit";
import { Container, FloatTile, IconTile, Section, SectionHead } from "./primitives";

/** /reviews in the landing layout language. Copy: formerly `(marketing)/_content/reviews.ts`. */
export function ReviewsPage() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "Reviews" }]}
        label="Reviews"
        lead="Borrowers,"
        muted="in their own words"
        sub="We're a new platform earning trust the honest way. Verified borrower reviews will appear here as our community grows."
        floats={
          <>
            <FloatTile className="left-[7%] top-[22%]" rotate={-8}>
              <IconTile size={78} className="text-gold-dark">
                <MessageSquareQuote size={32} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
            <FloatTile className="right-[8%] top-[30%]" rotate={7} delay={2}>
              <IconTile size={68} className="text-ink">
                <Ellipsis size={28} />
              </IconTile>
            </FloatTile>
          </>
        }
      />

      <Section labelledBy="soon-title" className="pb-8 sm:pb-10">
        <Container>
          <div className="reveal relative mx-auto max-w-[680px]">
            <figure className="lp-card m-0 rounded-[28px] p-8 text-center sm:p-11">
              <IconTile size={56} className="mx-auto text-gold-dark">
                <MessageSquareQuote size={24} />
              </IconTile>
              <h2 id="soon-title" className="lp-h3 mt-6 !text-[24px]">
                Verified reviews are coming soon
              </h2>
              <blockquote className="m-0 mt-4 text-[16px] leading-[1.7] text-slate">
                We&apos;d rather show you real, verified borrower reviews than invent them. As customers complete their loans,
                their verified feedback and independent ratings will be published here. In the meantime, see exactly what
                you&apos;ll repay on our{" "}
                <Link href="/calculator" className="text-ink underline decoration-gold underline-offset-4 hover:text-gold-dark">
                  calculator
                </Link>
                .
              </blockquote>
            </figure>
          </div>
        </Container>
      </Section>

      <Section labelledBy="where-title">
        <Container>
          <SectionHead
            label="Find us everywhere"
            id="where-title"
            lead="Where people talk about us"
            sub="Reviews, reels and conversations across every platform we show up on."
          />
          <ul className="m-0 mt-14 grid list-none gap-5 p-0 md:grid-cols-3">
            {[
              { icon: Facebook, t: "Facebook", s: "Join our growing community." },
              { icon: Instagram, t: "Instagram", s: "Follow our reels & customer stories." },
              { icon: Search, t: "Google", s: "Read honest reviews from real users." },
            ].map(({ icon: Icon, t, s }, i) => (
              <li key={t} className={`reveal ${i ? `d${i}` : ""} lp-card rounded-[24px] p-7 text-center`}>
                <IconTile size={56} className="mx-auto text-ink">
                  <Icon aria-hidden size={24} strokeWidth={1.8} />
                </IconTile>
                <h3 className="lp-h3 mt-5">{t}</h3>
                <p className="m-0 mt-1.5 text-[14.5px] text-slate">{s}</p>
              </li>
            ))}
          </ul>
        </Container>
      </Section>
    </div>
  );
}
