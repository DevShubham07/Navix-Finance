import * as React from "react";
import { Calculator } from "lucide-react";
import { RepaymentCard, StickyNote } from "./fragments";
import { PageHero } from "./page-kit";
import { CalendarTile, FloatTile, IconTile } from "./primitives";

/** Hero for /calculator. Copy: formerly the `.page-hero` of `(marketing)/_content/calculator.ts`. */
export function CalculatorHero() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "Calculator & Rates" }]}
        label="Calculator"
        lead="Loan calculator"
        muted="& transparent rates"
        sub="Adjust your amount and tenure to instantly see your interest, APR and total payable. What you see here is what you'll repay: no hidden charges, no advance fees."
        floats={
          <>
            <FloatTile className="left-[5%] top-[16%]" rotate={-7}>
              <IconTile size={80} className="text-gold-dark">
                <Calculator size={34} strokeWidth={1.7} />
              </IconTile>
            </FloatTile>
            <FloatTile className="bottom-[12%] left-[10%]" rotate={-4} delay={2}>
              <StickyNote>
                1% per day.
                <br />
                No pre-closure charge.
              </StickyNote>
            </FloatTile>
            <FloatTile className="right-[4%] top-[14%]" rotate={6} delay={1}>
              <RepaymentCard />
            </FloatTile>
            <FloatTile className="bottom-[14%] right-[13%]" rotate={9} delay={3}>
              <CalendarTile day="30" month="Jun" size={72} />
            </FloatTile>
          </>
        }
      />
    </div>
  );
}
