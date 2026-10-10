import * as React from "react";
import Link from "next/link";
import { ArrowRight, Briefcase, Compass, HeartPulse, Sparkles } from "lucide-react";
import { BRAND } from "@/lib/brand";
import { PageHero } from "./page-kit";
import { BlueprintGrid, BlueprintItem, Container, FloatTile, IconTile, Section, SectionHead } from "./primitives";

/** /careers in the landing layout language. Copy (and the open roles) from `(marketing)/_content/careers.ts`. */
const ROLES = [
  { title: "Senior Frontend Engineer", meta: ["Engineering", "Bengaluru / Hybrid", "Full-time"] },
  { title: "Product Designer (UX)", meta: ["Design", "Bengaluru / Hybrid", "Full-time"] },
  { title: "Risk & Compliance Analyst", meta: ["Risk", "Bengaluru", "Full-time"] },
  { title: "Customer Support Lead", meta: ["Operations", "Bengaluru", "Full-time"] },
];

export function CareersPage() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "Careers" }]}
        label="Careers"
        lead="Build fair finance"
        muted="with us"
        sub="We're a small, mission-driven team in Bengaluru reimagining how India borrows. Come help us make credit calm, clear and fair."
        floats={
          <>
            <FloatTile className="left-[7%] top-[24%]" rotate={-8}>
              <IconTile size={78} className="text-gold-dark">
                <Briefcase size={32} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
            <FloatTile className="right-[8%] top-[28%]" rotate={7} delay={2}>
              <IconTile size={68} className="text-ink">
                <Sparkles size={28} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
          </>
        }
      />
      <Section labelledBy="why-join" className="pb-10">
        <Container>
          <h2 id="why-join" className="sr-only">
            Why join us
          </h2>
          <BlueprintGrid cols={3} className="reveal">
            <BlueprintItem icon={<Compass size={22} strokeWidth={1.9} />} title="Real ownership">
              Small team, big surface area. Your work ships and matters from day one.
            </BlueprintItem>
            <BlueprintItem icon={<Sparkles size={22} strokeWidth={1.9} />} title="Mission with integrity">
              We hold a high bar on fairness and compliance. No dark patterns, ever.
            </BlueprintItem>
            <BlueprintItem icon={<HeartPulse size={22} strokeWidth={1.9} />} title="Healthy pace">
              Flexible, hybrid, and humane. We design for our customers&apos; wellbeing and our own.
            </BlueprintItem>
          </BlueprintGrid>
        </Container>
      </Section>
      <Section labelledBy="roles-title" className="pt-10">
        <Container className="max-w-[960px]">
          <SectionHead label="Open roles" id="roles-title" lead="Where you fit in" />
          <ul className="m-0 mt-12 grid list-none gap-3 p-0">
            {ROLES.map((r) => (
              <li
                key={r.title}
                className="reveal lp-card flex flex-col gap-4 rounded-[22px] p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6"
              >
                <div className="min-w-0">
                  <h3 className="lp-h3">{r.title}</h3>
                  <ul className="m-0 mt-2.5 flex list-none flex-wrap gap-2 p-0">
                    {r.meta.map((m) => (
                      <li key={m} className="rounded-full bg-grey-100 px-3 py-1 text-[12.5px] text-slate">
                        {m}
                      </li>
                    ))}
                  </ul>
                </div>
                <Link
                  href="/contact"
                  aria-label={`Apply for ${r.title}`}
                  className="group inline-flex h-10 shrink-0 items-center gap-1.5 self-start rounded-full border border-[rgb(var(--c-line-2))] bg-paper px-4 text-[14px] font-semibold text-ink shadow-pill hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark sm:self-auto"
                >
                  Apply <ArrowRight aria-hidden size={15} className="transition-transform group-hover:translate-x-0.5" />
                </Link>
              </li>
            ))}
          </ul>
          <p className="reveal m-0 mt-6 rounded-[18px] border border-l-[3px] border-line border-l-gold bg-paper px-5 py-4 text-[14.5px] leading-[1.6] text-slate">
            Don&apos;t see your role? We&apos;re always glad to meet good people. Write to{" "}
            <b className="font-semibold text-ink">{BRAND.email}</b>.
          </p>
        </Container>
      </Section>
    </div>
  );
}
