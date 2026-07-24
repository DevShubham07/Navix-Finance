// Hand-authored blog posts (NOT design-export generated). One entry per slug; the [slug] route
// renders `html` via MarketingHtml and builds metadata + Article JSON-LD from the fields here.
//
// ⚠ YMYL content — reviewed for factual alignment with DhanBoost (platform not lender; ₹5,000–₹10,00,000;
// 1%/day interest; processing fee + GST netted from disbursal; no advance fee; 2%/day late fee capped
// at 30 days; salary-linked single repayment). Have compliance sign off before treating as final.

export type BlogPost = {
  slug: string;
  title: string;
  description: string;
  category: string;
  readMin: number;
  date: string; // display, e.g. "Jun 2026"
  datePublished: string; // ISO, for Article schema
  html: string;
};

function page(p: {
  category: string;
  title: string;
  lede: string;
  readMin: number;
  date: string;
  body: string;
}): string {
  return `<section class="page active" id="blog-post">
  <div class="page-hero"><div class="wrap">
    <div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; <a href="/blog" data-link="">Resources</a> &nbsp;/&nbsp; ${p.category}</div>
    <h1>${p.title}</h1>
    <p>${p.lede}</p>
    <div class="bmeta" style="margin-top:14px;display:flex;gap:16px;color:var(--muted);font-size:.9rem"><span>${p.readMin} min read</span><span>${p.date}</span></div>
  </div></div>
  <div class="sec"><div class="wrap"><div class="prose" style="max-width:760px;margin:0 auto">
    ${p.body}
    <p style="margin-top:32px;font-size:.86rem;color:var(--muted)"><em>This article is general information, not financial advice. Loans are offered by DhanBoost's RBI-registered NBFC partners; all rates and charges are set out in your Key Fact Statement before you accept an offer.</em></p>
    <div style="text-align:center;margin-top:32px"><a href="/blog" class="btn btn-ghost" data-link="">← All resources</a></div>
  </div></div></div>
</section>`;
}

export const POSTS: Record<string, BlogPost> = {
  "how-to-read-a-kfs": {
    slug: "how-to-read-a-kfs",
    title: "How to read a Key Fact Statement (KFS)",
    description:
      "The Key Fact Statement is the one document that tells you the true cost of a loan. Here's how to read every line before you sign.",
    category: "Borrowing 101",
    readMin: 6,
    date: "Jun 2026",
    datePublished: "2026-06-15",
    html: page({
      category: "Borrowing 101",
      title: "How to read a Key Fact Statement (KFS)",
      lede: "The one document that tells you the true cost of any loan — and exactly what to check before you sign.",
      readMin: 6,
      date: "Jun 2026",
      body: `
    <h2>What is a Key Fact Statement?</h2>
    <p>The Key Fact Statement (KFS) is a short, standardised summary that every regulated lender in India must give you before you accept a loan. It exists so you can see the real cost of borrowing in one place, in plain terms, without hunting through pages of legal text. On DhanBoost, the KFS is issued by the partner NBFC — the lender of record — and you receive it before you e-sign anything.</p>
    <h2>Which numbers actually matter?</h2>
    <p>Five figures do most of the work:</p>
    <ul>
      <li><b>Loan amount (principal):</b> the amount sanctioned to you — on DhanBoost, anywhere from ₹5,000 to ₹10,00,000.</li>
      <li><b>Interest rate:</b> how the interest is calculated. DhanBoost loans use a simple 1% per day on the principal, over your actual tenure.</li>
      <li><b>Fees:</b> a processing fee plus applicable GST. These are <em>deducted from your disbursal</em>, not charged upfront — so you never pay an advance fee out of pocket.</li>
      <li><b>Net disbursed amount:</b> what actually lands in your bank account after the fee and GST are netted off. Always check this against what you expected.</li>
      <li><b>Total amount payable:</b> principal plus interest over the tenure — the single number you'll repay on your due date.</li>
    </ul>
    <h2>Why the "net disbursed" line is the one people miss</h2>
    <p>A loan of ₹10,000 does not put ₹10,000 in your account. The processing fee and GST are taken from the disbursal, so you receive a little less — and you repay the full principal plus interest. This is normal and disclosed, but it surprises first-time borrowers. Read the net-disbursed line first, then the total-payable line, and you'll know exactly what you're getting and what you're giving back.</p>
    <h2>Check the due date and the late-payment terms</h2>
    <p>DhanBoost loans are salary-linked and repaid in a single instalment on or after your salary date. Your KFS shows the exact due date and the total due. It also states the late-payment fee: 2% per day on the overdue principal, capped at 30 days. Knowing this before you sign means there are no surprises if something slips.</p>
    <h2>A 60-second KFS checklist</h2>
    <ul>
      <li>Does the <b>net disbursed</b> amount match what you need?</li>
      <li>Is the <b>total payable</b> affordable on your due date?</li>
      <li>Do you recognise the <b>lender of record</b> (the NBFC)?</li>
      <li>Are there <b>zero advance fees</b>? (There should be — DhanBoost never charges one.)</li>
      <li>Are the <b>prepayment terms</b> clear? (DhanBoost has no pre-closure charges.)</li>
    </ul>
    <p>If every line makes sense and the total payable fits your budget, you're ready to proceed. If anything is unclear, ask before you sign — a good lender will always explain the KFS line by line.</p>`,
    }),
  },

  "signs-of-a-loan-scam": {
    slug: "signs-of-a-loan-scam",
    title: "5 signs of a loan scam (and how to avoid them)",
    description:
      "Advance-fee demands, pressure tactics and fake apps are the classic red flags. Learn the five signs of a loan scam and how to stay safe.",
    category: "Stay safe",
    readMin: 5,
    date: "Jun 2026",
    datePublished: "2026-06-10",
    html: page({
      category: "Stay safe",
      title: "5 signs of a loan scam (and how to avoid them)",
      lede: "Anyone asking for an advance fee is a red flag. Here's how to spot loan fraud and protect yourself.",
      readMin: 5,
      date: "Jun 2026",
      body: `
    <p>Digital lending is fast and convenient — and that speed is exactly what fraudsters exploit. The good news: almost every loan scam gives itself away with the same handful of tells. Learn these five and you'll spot trouble before it costs you anything.</p>
    <h2>1. They ask for an advance or "processing" fee upfront</h2>
    <p>This is the single biggest red flag. Legitimate lenders never ask you to pay a fee <em>before</em> disbursal. On DhanBoost, fees are netted from your disbursal and disclosed in the Key Fact Statement — you never send money to receive a loan. If anyone demands an upfront payment to "release", "unlock" or "insure" your loan, stop.</p>
    <h2>2. They pressure you to act immediately</h2>
    <p>"Offer expires in 10 minutes." "Pay now or lose your approval." Urgency is a manipulation tactic designed to stop you thinking. A real lender gives you time to read your KFS and decide. If you feel rushed, that's the moment to slow down.</p>
    <h2>3. The app or link isn't official</h2>
    <p>Scammers clone brand names and logos. Always check you're on the official domain — for us, that's <b>dhanboost.com</b> — and that emails come from <b>info@dhanboost.com</b>. Be wary of loan offers arriving by random WhatsApp, SMS or Telegram links, or apps downloaded from outside official stores.</p>
    <h2>4. There's no clear lender or registration</h2>
    <p>Every legitimate loan in India is made by an RBI-registered entity. If you can't tell who the actual lender is, or there's no Key Fact Statement, walk away. DhanBoost is a platform; the loan is sanctioned and disbursed by our RBI-registered NBFC partners, and that's stated clearly before you sign.</p>
    <h2>5. They ask for OTPs, full card numbers or remote access</h2>
    <p>No genuine lender needs your OTP, full card/CVV, or remote-control access to your phone. These requests exist only to drain your account. Never share them.</p>
    <h2>How to stay safe</h2>
    <ul>
      <li>Never pay to receive a loan. No advance fees, ever.</li>
      <li>Verify the lender and read the KFS before signing.</li>
      <li>Use only official channels; check the domain and sender.</li>
      <li>Never share OTPs, full card details or device access.</li>
      <li>Report anything suspicious in DhanBoost's name to <b>info@dhanboost.com</b>.</li>
    </ul>
    <p>Fraud thrives on urgency and secrecy. Slow down, verify, and you take away its power.</p>`,
    }),
  },

  "what-affects-your-credit-score": {
    slug: "what-affects-your-credit-score",
    title: "What actually affects your credit score",
    description:
      "Payment history, credit utilisation, age of accounts, enquiries and mix — what really moves your credit score, and the myths that don't.",
    category: "Credit",
    readMin: 7,
    date: "May 2026",
    datePublished: "2026-05-20",
    html: page({
      category: "Credit",
      title: "What actually affects your credit score",
      lede: "The myths, the facts, and the small habits that build a healthy score over time.",
      readMin: 7,
      date: "May 2026",
      body: `
    <p>Your credit score is a three-digit summary of how you've handled borrowing. In India it usually runs from 300 to 900, and lenders use it as one input when they assess you. It isn't magic, and it isn't fixed — it responds to a few clear behaviours. Here's what actually moves it.</p>
    <h2>Payment history — the biggest factor</h2>
    <p>Paying on time, every time, is the strongest thing you can do. A single missed or late payment can dent your score, and the effect lingers. Set reminders, automate where you can, and if you're going to be late, talk to your lender early. On DhanBoost, your due date is salary-linked and shown clearly, precisely so it's easy to pay on time.</p>
    <h2>Credit utilisation — how much of your limit you use</h2>
    <p>If you have credit cards or revolving limits, using a large share of them signals stress. A common rule of thumb is to keep usage well below 30% of your limit. Lower utilisation generally helps your score.</p>
    <h2>Age and mix of credit</h2>
    <p>A longer track record of well-managed accounts helps. So does a healthy mix — for example, a card plus a loan repaid responsibly — because it shows you can handle different kinds of credit. This is a smaller factor, and it builds naturally over time; don't open accounts just to "improve the mix".</p>
    <h2>Hard enquiries</h2>
    <p>Every time you formally apply for credit, a "hard enquiry" is recorded, and several in a short window can look like you're desperate for funds. Checking your <em>own</em> score, or checking eligibility that doesn't trigger a formal enquiry, does not hurt you. On DhanBoost, checking eligibility does not impact your score — a formal enquiry only happens if you proceed and accept an offer.</p>
    <h2>Common myths</h2>
    <ul>
      <li><b>"Checking my score lowers it."</b> False — checking your own score is a soft enquiry and has no impact.</li>
      <li><b>"Closing old cards helps."</b> Often the opposite — it can shorten your history and raise utilisation.</li>
      <li><b>"Income determines my score."</b> No — your score reflects credit behaviour, not salary (though lenders look at income separately).</li>
    </ul>
    <h2>Small habits that compound</h2>
    <ul>
      <li>Pay every bill on or before its due date.</li>
      <li>Keep card balances low relative to limits.</li>
      <li>Avoid a flurry of applications in a short period.</li>
      <li>Check your report periodically for errors and dispute them.</li>
    </ul>
    <p>A good score isn't built overnight — it's the by-product of borrowing only what you can repay and repaying it on time. Do that consistently and the number takes care of itself.</p>`,
    }),
  },

  "apr-vs-flat-rate": {
    slug: "apr-vs-flat-rate",
    title: "APR vs flat rate: what's the difference?",
    description:
      "Two loans with the same 'rate' can cost very different amounts. Here's how flat rates and APR differ — and why APR tells the truth.",
    category: "Money tips",
    readMin: 4,
    date: "May 2026",
    datePublished: "2026-05-08",
    html: page({
      category: "Money tips",
      title: "APR vs flat rate: what's the difference?",
      lede: "Why two loans advertising the same “rate” can cost very different amounts — explained simply.",
      readMin: 4,
      date: "May 2026",
      body: `
    <h2>The trap of the headline "rate"</h2>
    <p>Two lenders can both advertise "1.5% a month" and yet one loan costs far more than the other. The reason is <em>how</em> the rate is applied. The two you'll meet most are the flat rate and the APR (annual percentage rate) — and only one of them tells you the true cost.</p>
    <h2>Flat rate</h2>
    <p>A flat rate is charged on the <b>original</b> loan amount for the whole tenure, even as you pay the balance down. Because it ignores the fact that you owe less over time, the flat rate <em>understates</em> the real cost. A "1% flat" loan is effectively much more expensive than 1% sounds.</p>
    <h2>APR (annual percentage rate)</h2>
    <p>APR expresses the cost as a yearly percentage that accounts for how the balance reduces, and it usually folds in fees too. Because it's standardised, APR lets you compare two very different loans on a level playing field. When you see APR, you're closer to the truth.</p>
    <h2>A quick example</h2>
    <p>Imagine two lenders offering a similar-sounding rate on the same amount. The one quoting a flat rate can end up costing noticeably more than the one quoting an equivalent-looking APR, because the flat rate keeps charging on money you've already repaid. Same headline, different bill.</p>
    <h2>How DhanBoost keeps it honest</h2>
    <p>DhanBoost loans charge a simple 1% per day on the principal over your actual tenure, and — because it's a single-repayment, salary-linked loan — the Key Fact Statement shows you the exact rupee total you'll repay before you accept. Our calculator shows the same figure, including a representative APR, so you compare on total cost, not a headline.</p>
    <h2>The one habit to build</h2>
    <p>Whenever you compare loans, ignore the marketing rate and ask two questions: <b>what's the APR</b>, and <b>what's the total rupee amount I'll repay?</b> Those two numbers cut through almost every pricing trick.</p>`,
    }),
  },

  "when-a-short-term-loan-makes-sense": {
    slug: "when-a-short-term-loan-makes-sense",
    title: "When a short-term loan makes sense (and when it doesn't)",
    description:
      "A calm, honest framework for deciding whether a short-term loan is the right tool — and when it's the wrong one.",
    category: "Guides",
    readMin: 8,
    date: "Apr 2026",
    datePublished: "2026-04-18",
    html: page({
      category: "Guides",
      title: "When a short-term loan makes sense (and when it doesn't)",
      lede: "A calm, honest framework for deciding whether to borrow for a near-term need.",
      readMin: 8,
      date: "Apr 2026",
      body: `
    <p>A short-term loan is a tool, and like any tool it's right for some jobs and wrong for others. The difference between a smart borrow and a costly one usually comes down to a few honest questions you ask <em>before</em> you apply.</p>
    <h2>When it can make sense</h2>
    <ul>
      <li><b>A genuine, time-bound need with a clear payoff.</b> An essential expense you can't defer — a medical bill, a critical repair, a fee with a deadline — where a short bridge until your salary solves the problem cleanly.</li>
      <li><b>You already know how you'll repay.</b> A salary-linked loan works best when your due date lines up with money you know is coming. If the repayment plan is obvious, the loan is doing its job.</li>
      <li><b>The total cost is small relative to the benefit.</b> If a modest amount of interest saves you a large late penalty, a lost deposit, or real hardship, the maths can favour borrowing.</li>
    </ul>
    <h2>When it doesn't</h2>
    <ul>
      <li><b>To fund wants, not needs.</b> Borrowing for discretionary spending you could delay rarely ends well — the purchase fades, the repayment stays.</li>
      <li><b>To repay another loan.</b> Rolling debt from one loan into another is a warning sign. It usually grows the problem rather than solving it.</li>
      <li><b>When repayment isn't realistic.</b> If you can't clearly see how you'll clear it on the due date, the honest answer is to wait, trim the expense, or find another way.</li>
    </ul>
    <h2>A five-question gut check</h2>
    <ol>
      <li>Is this a need or a want?</li>
      <li>Exactly how and when will I repay it?</li>
      <li>What's the <b>total</b> I'll repay, and can I afford it on my due date?</li>
      <li>What happens if my income is delayed that month?</li>
      <li>Is there a cheaper or slower option that still solves the problem?</li>
    </ol>
    <h2>Borrow the right amount, not the maximum</h2>
    <p>Approval for a limit is not an instruction to use all of it. Borrow the smallest amount that solves your problem, over the shortest sensible tenure. On DhanBoost you can see the exact repayment for any amount on the calculator before you commit — use it to find the number that's comfortable, not just possible.</p>
    <h2>The bottom line</h2>
    <p>A short-term loan is at its best when it's a small, planned bridge to money you know is coming. If you can answer the five questions above with confidence, you're borrowing well. If you can't, that hesitation is useful information — listen to it.</p>`,
    }),
  },

  "repaying-on-dhanboost": {
    slug: "repaying-on-dhanboost",
    title: "Repaying on DhanBoost: UPI, net-banking & auto-debit",
    description:
      "Every way to repay your DhanBoost loan — UPI, net-banking and auto-debit — plus how repaying early saves you interest.",
    category: "Product",
    readMin: 3,
    date: "Apr 2026",
    datePublished: "2026-04-05",
    html: page({
      category: "Product",
      title: "Repaying on DhanBoost: UPI, net-banking & auto-debit",
      lede: "A quick walkthrough of every way to repay — and how clearing early saves you money.",
      readMin: 3,
      date: "Apr 2026",
      body: `
    <h2>One repayment, on your salary date</h2>
    <p>A DhanBoost loan is designed to be simple: you repay it in a <b>single instalment</b> on or after your salary date. There are no EMIs to track. Your exact due date and total amount are shown in your Key Fact Statement and on your dashboard, so you always know what's due and when.</p>
    <h2>The ways to pay</h2>
    <ul>
      <li><b>UPI:</b> the fastest option — pay from any UPI app to the details shown on your repayment screen. Payments usually reflect within minutes.</li>
      <li><b>Net-banking:</b> transfer from your bank's net-banking to the account shown on your dashboard. Keep the reference number until it's confirmed.</li>
      <li><b>Auto-debit:</b> if you've set up a mandate, the due amount is collected automatically on your due date — so you can't forget. Just keep enough balance in the account.</li>
    </ul>
    <h2>Repaying early saves you interest</h2>
    <p>DhanBoost charges 1% per day on the principal over the actual tenure, and there are <b>no pre-closure or prepayment charges</b>. That means clearing your loan early genuinely reduces what you pay — you're only charged interest for the days you actually held the loan. If salary lands early, paying early is a small, easy win.</p>
    <h2>If you might miss the date</h2>
    <p>Life happens. If you think you'll be late, contact us early at <b>info@dhanboost.com</b>. A late payment attracts a fee of 2% per day on the overdue principal (capped at 30 days) and can affect your credit score, so it's always worth talking to us before the date rather than after.</p>
    <h2>After you repay</h2>
    <p>Once your payment is confirmed, your loan is marked closed and you're free to borrow again in future if you need to. Keep your payment confirmation for your records — and that's it. Simple by design.</p>`,
    }),
  },
};

export const POST_SLUGS = Object.keys(POSTS);
