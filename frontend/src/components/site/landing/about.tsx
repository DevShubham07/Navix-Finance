import * as React from "react";
import { HeartHandshake, IndianRupee, ShieldCheck, Users } from "lucide-react";
import { Avatar } from "@/components/kit";
import { CheckTile, StickyNote } from "./fragments";
import { CtaBand, PageHero } from "./page-kit";
import { BlueprintGrid, BlueprintItem, Container, FloatTile, IconTile, Section, SectionHead } from "./primitives";

/** /about in the landing layout language. Copy: formerly `(marketing)/_content/about.ts`. */
export function AboutPage() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "About Us" }]}
        label="About us"
        lead="Making credit calm,"
        muted="clear and fair"
        sub="DhanBoost was built on a simple belief: borrowing money shouldn't feel stressful or sneaky. We pair thoughtful technology with a fair, salary-linked product to make short-term credit genuinely transparent."
        floats={
          <>
            <FloatTile className="left-[5%] top-[16%]" rotate={-7}>
              <StickyNote>
                No fine print.
                <br />
                No advance fees.
                <br />
                No pressure.
              </StickyNote>
            </FloatTile>
            <FloatTile className="bottom-[14%] left-[15%]" rotate={8} delay={2}>
              <CheckTile size={72} />
            </FloatTile>
            <FloatTile className="right-[6%] top-[18%]" rotate={7} delay={1}>
              <IconTile size={78} className="text-gold-dark">
                <ShieldCheck size={32} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
            <FloatTile className="bottom-[12%] right-[12%]" rotate={-6} delay={3}>
              <IconTile size={64} className="text-ink">
                <HeartHandshake size={27} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
          </>
        }
      />

      <Section labelledBy="story-title">
        <Container className="grid items-center gap-12 lg:grid-cols-2 lg:gap-16">
          <div className="reveal">
            <SectionHead align="left" label="Our story" id="story-title" lead="A better way to borrow," muted="for everyday India" />
            <p className="m-0 mt-6 max-w-[560px] text-[17px] leading-[1.65] text-ink">
              Too many people meet a small, urgent expense with confusing apps, hidden fees and pushy agents. We started
              DhanBoost to change that: a premium, honest experience where you always know exactly what you&apos;ll repay.
            </p>
            <p className="m-0 mt-4 max-w-[560px] text-[15.5px] leading-[1.65] text-slate">
              As a digital lending platform, we hold ourselves to high standards of transparency and responsible lending:
              clear terms, no hidden charges, and fairness built into every step.
            </p>
          </div>
          <figure className="reveal d1 relative m-0 overflow-hidden rounded-[28px] bg-navy p-8 text-white sm:p-11">
            <div
              aria-hidden
              className="pointer-events-none absolute -right-20 -top-24 h-72 w-72 rounded-full"
              style={{ background: "radial-gradient(circle, rgb(var(--c-gold-500) / .35), transparent 70%)" }}
            />
            <p className="relative m-0 text-[12.5px] font-semibold uppercase tracking-[0.1em] text-gold-400">Our mission</p>
            <blockquote className="relative m-0 mt-6 text-[clamp(1.45rem,2.4vw,1.9rem)] font-medium leading-[1.25] tracking-[-0.03em] text-white">
              “To make small-ticket credit transparent, fast and humane for every working Indian.”
            </blockquote>
            <figcaption className="relative mt-8 flex items-center gap-3 text-[14px] text-white/80">
              <Avatar name="DhanBoost" size={38} className="border-navy" />
              No fine print. No advance fees. No pressure. Just clear terms and friendly support when you need a hand.
            </figcaption>
          </figure>
        </Container>
      </Section>

      <div className="px-3 sm:px-5">
        <div className="mx-auto max-w-[1400px] rounded-[24px] border border-line bg-surface sm:rounded-[32px]">
          <Section labelledBy="values-title">
            <Container>
              <SectionHead label="What we stand for" id="values-title" lead="Our values" muted="built in, not bolted on" />
              <BlueprintGrid cols={3} className="reveal mt-14">
                <BlueprintItem icon={<IndianRupee size={22} strokeWidth={1.9} />} title="Transparency first">
                  We show every charge, upfront, in plain language. If we wouldn&apos;t accept the terms, we won&apos;t offer them.
                </BlueprintItem>
                <BlueprintItem icon={<Users size={22} strokeWidth={1.9} />} title="People over funnels">
                  Real human support and respectful collections. We design for your wellbeing, not just conversion.
                </BlueprintItem>
                <BlueprintItem icon={<ShieldCheck size={22} strokeWidth={1.9} />} title="Compliance by design">
                  Secure data handling, fair terms, and a clear grievance path, built in, not bolted on.
                </BlueprintItem>
              </BlueprintGrid>
            </Container>
          </Section>
        </div>
      </div>

      <div className="pt-[clamp(56px,7vw,96px)]">
        <CtaBand
          id="about-cta"
          lead="Come build with us"
          sub="We're a small team with a big mission. If fair finance excites you, we'd love to talk."
          primary={{ href: "/careers", label: "See open roles" }}
          secondary={{ href: "/contact", label: "Get in touch" }}
        />
      </div>
    </div>
  );
}
