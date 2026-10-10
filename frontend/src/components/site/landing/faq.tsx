import * as React from "react";
import { Plus } from "lucide-react";
import { html } from "@/app/(marketing)/_content/faq";
import { CtaBand, PageHero } from "./page-kit";
import { parseFaq, parseHero } from "./legacy-html";
import { Container } from "./primitives";

/**
 * /faq in the landing layout language. Questions and answers are read from
 * `(marketing)/_content/faq.ts` (the same copy the FAQ JSON-LD mirrors), grouped by topic.
 */
export function FaqPage() {
  const hero = parseHero(html);
  const groups = parseFaq(html);
  const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
  return (
    <div className="lp">
      <PageHero trail={[{ label: hero.crumb }]} label="FAQs" lead="Frequently asked" muted="questions" sub={hero.lead} />
      <Container className="grid gap-10 py-[clamp(48px,7vw,96px)] lg:grid-cols-[220px_minmax(0,1fr)] lg:gap-16">
        <nav aria-label="FAQ topics" className="lg:sticky lg:top-[100px] lg:self-start">
          <p className="m-0 mb-3 text-[12px] font-semibold uppercase tracking-[0.08em] text-slate">Topics</p>
          <ul className="m-0 flex list-none flex-wrap gap-2 p-0 lg:grid lg:gap-0.5 lg:border-l lg:border-line">
            {groups.map((g) => (
              <li key={g.title}>
                <a
                  href={`#${slug(g.title)}`}
                  className="block rounded-full border border-line bg-paper px-3.5 py-1.5 text-[13.5px] text-slate hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark lg:-ml-px lg:rounded-none lg:border-0 lg:border-l-2 lg:border-transparent lg:bg-transparent lg:py-1.5 lg:pl-4 lg:hover:border-gold"
                >
                  {g.title}
                </a>
              </li>
            ))}
          </ul>
        </nav>
        <div className="grid min-w-0 gap-12">
          {groups.map((g) => (
            <section key={g.title} id={slug(g.title)} aria-labelledby={`${slug(g.title)}-h`} className="scroll-mt-[100px]">
              <h2 id={`${slug(g.title)}-h`} className="lp-h2 !text-[clamp(1.5rem,2.6vw,2rem)]">
                {g.title}
              </h2>
              <div className="mt-5 grid gap-3">
                {g.items.map((it) => (
                  <details key={it.q} className="lp-qa lp-card group rounded-[20px] px-5 py-1 sm:px-6">
                    <summary className="flex items-center justify-between gap-4 rounded-[14px] py-4 focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark">
                      <h3 className="m-0 text-[15.5px] font-medium leading-snug tracking-[-0.015em] text-ink">{it.q}</h3>
                      <span className="lp-qa-icon grid h-8 w-8 shrink-0 place-items-center rounded-full bg-grey-100 text-ink transition-transform duration-300">
                        <Plus aria-hidden size={16} />
                      </span>
                    </summary>
                    <div
                      className="pb-5 pr-2 text-[15px] leading-[1.65] text-slate sm:pr-10 [&_p]:m-0"
                      dangerouslySetInnerHTML={{ __html: it.a }}
                    />
                  </details>
                ))}
              </div>
            </section>
          ))}
        </div>
      </Container>
      <CtaBand
        id="faq-cta"
        lead="Still need a hand?"
        sub="Our team is friendly, fast and actually human. Reach out and we'll sort it."
        primary={{ href: "/contact", label: "Contact support" }}
        secondary={{ href: "/help", label: "Help Center" }}
      />
    </div>
  );
}
