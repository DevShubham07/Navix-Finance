import * as React from "react";
import { ArrowRight, Building2, Landmark, MonitorSmartphone, UserRound } from "lucide-react";
import Link from "next/link";
import { PageHero } from "./page-kit";
import { BlueprintGrid, BlueprintItem, Container, FloatTile, IconTile, Section, SectionHead } from "./primitives";

/** /partners in the landing layout language. Copy: formerly `(marketing)/_content/partners.ts`. */
const PARTNERS = [
  "Arthveda Capital Private Limited",
  "Sentinel Finserv Limited",
  "Pragati Credit Solutions Pvt. Ltd.",
  "Meridian Finance Limited",
];

export function PartnersPage() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "Lending Partners" }]}
        label="Lending partners"
        lead="Loans by"
        muted="RBI-registered NBFCs"
        sub="DhanBoost is a technology platform, not a lender. Every loan is sanctioned and disbursed by one of our RBI-registered NBFC lending partners, who remain the lender of record."
        floats={
          <>
            <FloatTile className="left-[7%] top-[24%]" rotate={-7}>
              <IconTile size={80} className="text-gold-dark">
                <Landmark size={34} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
            <FloatTile className="right-[7%] top-[30%]" rotate={8} delay={2}>
              <IconTile size={70} className="text-ink">
                <Building2 size={30} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
          </>
        }
      />
      <Section labelledBy="partners-list" className="pb-10">
        <Container>
          <h2 id="partners-list" className="sr-only">
            Our lending partners
          </h2>
          <ul className="m-0 grid list-none gap-5 p-0 md:grid-cols-2">
            {PARTNERS.map((p, i) => (
              <li key={p} className={`reveal ${i % 2 ? "d1" : ""} lp-card flex items-start gap-5 rounded-[24px] p-6 sm:p-7`}>
                <span aria-hidden className="grid h-16 w-16 shrink-0 place-items-center rounded-[18px] border border-dashed border-navix-200 bg-grey-50 text-center text-[10.5px] font-medium leading-tight text-slate">
                  NBFC
                  <br />
                  logo
                </span>
                <div className="min-w-0">
                  <span className="inline-flex rounded-full bg-gold-50 px-2.5 py-0.5 text-[12px] font-medium text-gold-dark">Lending Partner</span>
                  <h3 className="lp-h3 mt-3">{p}</h3>
                  <p className="m-0 mt-1.5 text-[14.5px] leading-[1.6] text-slate">
                    Lender of record · sanction letter &amp; Key Fact Statement issued by the NBFC.
                  </p>
                  <Link
                    href="/grievance"
                    className="group mt-3 inline-flex items-center gap-1.5 rounded text-[14px] font-medium text-ink hover:text-gold-dark focus-visible:outline focus-visible:outline-2 focus-visible:outline-gold-dark"
                  >
                    View disclosures <ArrowRight aria-hidden size={14} className="transition-transform group-hover:translate-x-0.5" />
                  </Link>
                </div>
              </li>
            ))}
          </ul>
        </Container>
      </Section>
      <Section labelledBy="roles-title" className="pt-10">
        <Container>
          <SectionHead label="How the partnership works" id="roles-title" lead="Clear roles," muted="full compliance" />
          <BlueprintGrid cols={3} className="reveal mt-14">
            <BlueprintItem icon={<MonitorSmartphone size={22} strokeWidth={1.9} />} title="DhanBoost (the platform)">
              We build the technology, the application experience and customer support, and connect you to the right NBFC.
            </BlueprintItem>
            <BlueprintItem icon={<Landmark size={22} strokeWidth={1.9} />} title="NBFC (the lender)">
              The RBI-registered NBFC assesses your application, sets the rate, sanctions and disburses the loan, and is the
              lender of record.
            </BlueprintItem>
            <BlueprintItem icon={<UserRound size={22} strokeWidth={1.9} />} title="You (the borrower)">
              You get a transparent Key Fact Statement, fair terms, and a clear grievance redressal channel, every time.
            </BlueprintItem>
          </BlueprintGrid>
        </Container>
      </Section>
    </div>
  );
}
