// Regenerated from the NAVIX "calendar" design export — front page (#home).
// No fabricated stats or third-party lender claims; includes the auto-advancing how-it-works
// journey + the repayment calendar. Links rewritten to the real app routes.
/* eslint-disable */
export const html = `<section class="page active" id="home">

  <!-- HERO -->
  <div class="hero"><span class="hero-blob hb-1"></span><span class="hero-blob hb-2"></span><div class="wrap"><div class="hero-grid">
    <div>
      <span class="eyebrow reveal d1">Digital Lending Platform</span>
      <h1 class="hero-h1" aria-label="Instant personal loans. Fully digital. Fairly priced.">
        <span class="hl"><span class="w" style="--i:0">Instant</span> <span class="w" style="--i:1">personal</span> <span class="w" style="--i:2">loans.</span></span><br>
        <span class="hl l2"><span class="w" style="--i:3">Fully</span> <span class="w" style="--i:4">digital.</span></span><br>
        <span class="hl l3"><span class="w" style="--i:5">Fairly</span> <span class="w" style="--i:6">priced.</span></span>
      </h1>
      <p class="lead reveal d3">A paperless application and transparent terms, from first tap to funds in your account. Borrow ₹5,000 to ₹1,00,000 with no advance fees, ever.</p>
      <div class="hero-cta reveal d4">
        <a href="/signup/mobile-otp" class="btn btn-gold btn-lg" data-link="">Apply for a Loan <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14M12 5l7 7-7 7"></path></svg></a>
        <a href="/how-it-works" class="btn btn-ghost btn-lg" data-link="">How It Works</a>
      </div>
      <div class="hero-trust">
        <div class="ht"><span class="htico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"></path></svg></span><div><b>Fully digital</b><small>Apply in minutes</small></div></div>
        <div class="ht"><span class="htico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2 4 5v6c0 5 3.5 9 8 11 4.5-2 8-6 8-11V5l-8-3z"></path></svg></span><div><b>Transparent terms</b><small>No hidden fees</small></div></div>
        <div class="ht"><span class="htico"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><path d="M12 6v6l4 2"></path></svg></span><div><b>No advance fees</b><small>Ever</small></div></div>
      </div>
    </div>
    <div class="reveal d2" style="position:relative">
      <div class="offer-card" id="offerCard">
        <span class="offer-sheen"></span>
        <div class="offer-head">
          <div class="offer-id">
            <span class="offer-logo"><img src="/navix-mark.png" alt="NAVIX" width="26" height="26" style="object-fit:contain" /></span>
            <div><b>NAVIX</b><small>Personal loan offer</small></div>
          </div>
          <span class="offer-status" id="offerStatus"><span class="os-dot"></span><span class="os-review">Reviewing</span><span class="os-ok">Approved</span></span>
        </div>
        <div class="offer-amount">
          <span class="oa-label">Loan amount</span>
          <div class="oa-value" id="offerAmt">₹0</div>
        </div>
        <div class="offer-stats">
          <div class="ost"><span class="ost-l">Daily rate</span><b><span class="num" id="offerRate">0.0</span><i>% / day</i></b></div>
          <div class="ost"><span class="ost-l">Tenure</span><b><span class="num" id="offerTen">0</span><i>days</i></b></div>
        </div>
        <div class="offer-track"><span class="offer-bar" id="offerBar"></span></div>
        <div class="offer-approved" id="offerApproved">
          <span class="oa-check"><svg viewBox="0 0 36 36" fill="none"><circle class="oac-ring" cx="18" cy="18" r="16" stroke="currentColor" stroke-width="2.5"></circle><path class="oac-tick" d="M11 18.5l4.4 4.4L25.5 13" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"></path></svg></span>
          <div class="oa-text"><b>Approved</b><small>Funds ready to disburse</small></div>
        </div>
      </div>
    </div>
  </div></div></div>

  <!-- WHY CHOOSE -->
  <div class="sec" id="s-why" style="padding-top:20px"><div class="wrap">
    <div class="sec-head"><span class="eyebrow center reveal">Why NAVIX</span><h2 class="reveal d1">Lending, reimagined to feel effortless</h2><p class="reveal d2">A transparent, fast and hassle-free experience built around you — with no hidden costs and no paperwork.</p></div>
    <div class="grid-3">
      <div class="fcard reveal"><div class="fico"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"></path></svg></div><h3>Lightning-fast application</h3><p>Get an eligibility decision in minutes and funds sent straight to your account after you accept.</p></div>
      <div class="fcard reveal d1"><div class="fico"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><path d="M12 6v6l4 2"></path></svg></div><h3>100% transparent pricing</h3><p>Every rupee of interest and charges is shown upfront before you accept. No surprises, no advance fees.</p></div>
      <div class="fcard reveal d2"><div class="fico"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2 4 5v6c0 5 3.5 9 8 11 4.5-2 8-6 8-11V5l-8-3z"></path><path d="M9 12l2 2 4-4"></path></svg></div><h3>Strong data security</h3><p>Your data is protected with strong encryption and strict access controls. We never sell your information.</p></div>
      <div class="fcard reveal"><div class="fico"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="2" width="16" height="20" rx="2"></rect><path d="M8 6h8M8 10h8M8 14h3"></path></svg></div><h3>Minimal documentation</h3><p>Just PAN, Aadhaar and bank details — verified digitally. Complete everything from your phone.</p></div>
      <div class="fcard reveal d1"><div class="fico"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 3v18h18"></path><path d="M7 14l4-4 3 3 5-6"></path></svg></div><h3>Flexible repayment</h3><p>Repay via UPI, net-banking or auto-debit. No pre-closure or prepayment charges — clear early, save more.</p></div>
      <div class="fcard reveal d2"><div class="fico"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg></div><h3>Real human support</h3><p>Friendly support over chat, email and phone, six days a week — plus a transparent grievance redressal channel.</p></div>
    </div>
  </div></div>

  <!-- HOW IT WORKS PREVIEW -->
  <div class="sec" id="s-how" style="background:var(--grad-cream)"><div class="wrap">
    <div class="sec-head"><span class="eyebrow center reveal">How It Works</span><h2 class="reveal d1">From application to your account in 4 steps</h2><p class="reveal d2">A guided, fully online journey. Eligible customers are typically funded within 1–2 business days.</p></div>
    <div class="journey reveal d1" id="journey">
      <div class="jrny-rail">
        <span class="jrny-progress" id="jrnyProgress"></span>
        <button class="jrny-step on" data-i="0"><span class="js-dot">1</span><span class="js-cap">Apply</span></button>
        <button class="jrny-step" data-i="1"><span class="js-dot">2</span><span class="js-cap">Verify</span></button>
        <button class="jrny-step" data-i="2"><span class="js-dot">3</span><span class="js-cap">Accept</span></button>
        <button class="jrny-step" data-i="3"><span class="js-dot">4</span><span class="js-cap">Funds</span></button>
      </div>
      <div class="jrny-stage">
        <div class="jrny-panel on" data-i="0">
          <div class="jp-text">
            <span class="jp-kicker">Step 01 — You</span>
            <h3>Apply online in two minutes</h3>
            <p>Choose how much you need and share a few basics. No paperwork, no branch visit — it works on any phone.</p>
            <span class="jp-meta"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></svg> ~2-minute form</span>
          </div>
          <div class="jp-visual"><div class="mini-card">
            <div class="mc-row"><span>Loan amount</span><b>₹25,000</b></div>
            <div class="mc-track"><span class="mc-fill"></span><span class="mc-thumb"></span></div>
            <div class="mc-field f1">Purpose · Medical emergency</div>
            <div class="mc-field f2">Tenure · 30 days</div>
            <div class="mc-btn">Continue →</div>
          </div></div>
        </div>
        <div class="jrny-panel" data-i="1">
          <div class="jp-text">
            <span class="jp-kicker">Step 02 — Verification</span>
            <h3>Instant digital verification</h3>
            <p>Paperless KYC with PAN and Aadhaar, plus a quick eligibility check — all online.</p>
            <span class="jp-meta"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3 5 6v5c0 4.5 3 7.5 7 9 4-1.5 7-4.5 7-9V6l-7-3z"></path></svg> Bank-grade &amp; encrypted</span>
          </div>
          <div class="jp-visual"><div class="mini-card">
            <div class="vk-row"><span class="vk-ic">PAN</span> Identity verified <span class="vk-chk">✓</span></div>
            <div class="vk-row"><span class="vk-ic">AADHAAR</span> e-KYC complete <span class="vk-chk">✓</span></div>
            <div class="vk-row"><span class="vk-ic">BANK</span> Salary confirmed <span class="vk-chk">✓</span></div>
            <div class="vk-badge">Eligible — instant decision</div>
          </div></div>
        </div>
        <div class="jrny-panel" data-i="2">
          <div class="jp-text">
            <span class="jp-kicker">Step 03 — You</span>
            <h3>Review &amp; e-sign your offer</h3>
            <p>See your exact interest, APR and total repayment in a clear summary. Happy with it? Sign securely in a tap.</p>
            <span class="jp-meta"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"></path><path d="M14 3v5h5"></path></svg> Key Fact Statement upfront</span>
          </div>
          <div class="jp-visual"><div class="mini-card">
            <div class="kfs-row"><span>You borrow</span><b>₹25,000</b></div>
            <div class="kfs-row"><span>Interest</span><b>₹7,500</b></div>
            <div class="kfs-row apr"><span>Representative APR</span><b>365%</b></div>
            <div class="kfs-total"><span>You repay</span><b>₹32,500</b></div>
            <svg class="kfs-sign" viewBox="0 0 200 40"><path d="M6 28 C 26 6 44 34 64 18 C 84 2 104 4 132 24 C 150 36 176 18 194 14"></path></svg>
            <div class="kfs-esign">✓ e-signed · just now</div>
          </div></div>
        </div>
        <div class="jrny-panel" data-i="3">
          <div class="jp-text">
            <span class="jp-kicker">Step 04 — Money in</span>
            <h3>Funds land in your account</h3>
            <p>Funds are sent straight to your bank account. Repay easily via UPI, net-banking or auto-debit.</p>
            <span class="jp-meta"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 10l9-6 9 6"></path><path d="M5 10v9h14v-9"></path><path d="M9 19v-5h6v5"></path></svg> Disbursed in 24–48h</span>
          </div>
          <div class="jp-visual"><div class="mini-card funds">
            <div class="fn-coins"><i></i><i></i><i></i><i></i><i></i></div>
            <div class="fn-label">Account balance</div>
            <div class="fn-amt" id="fnAmt">₹0</div>
            <div class="fn-row"><span class="fn-chk">✓</span> Credited to your bank account</div>
          </div></div>
        </div>
      </div>
    </div>
    <div style="text-align:center;margin-top:48px" class="reveal d2"><a href="/how-it-works" class="btn btn-navy" data-link="">See the full process <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14M12 5l7 7-7 7"></path></svg></a></div>
  </div></div>

  <!-- CALCULATOR TEASER -->
  <div class="sec" id="s-calc"><div class="wrap">
    <div class="ctaband" style="background:var(--grad-cream);color:var(--ink);border:1px solid var(--line)">
      <div style="position:relative;display:grid;gap:40px;align-items:center;text-align:left" class="elig">
        <div>
          <span class="eyebrow reveal">Plan with confidence</span>
          <h2 class="reveal d1" style="color:var(--ink);text-align:left;margin:16px 0">Know your exact repayment<br>before you borrow</h2>
          <p class="reveal d2" style="color:var(--slate);text-align:left;margin:0 0 26px">Slide to your amount and tenure and instantly see interest, APR and total payable — with a clear breakdown and a transparent rate table. No fine print.</p>
          <a href="/calculator" class="btn btn-gold btn-lg reveal d3" data-link="">Open the calculator <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14M12 5l7 7-7 7"></path></svg></a>
        </div>
        <div class="reveal d2" style="display:grid;grid-template-columns:repeat(2,1fr);gap:14px">
          <div style="background:#fff;border:1px solid var(--line);border-radius:18px;padding:22px"><div style="font-size:.8rem;color:var(--muted);font-weight:700;text-transform:uppercase;letter-spacing:.08em">Loan range</div><div style="font-family:var(--font-display);font-size:1.7rem;color:var(--navy-800);font-weight:600">₹5K–₹1L</div></div>
          <div style="background:#fff;border:1px solid var(--line);border-radius:18px;padding:22px"><div style="font-size:.8rem;color:var(--muted);font-weight:700;text-transform:uppercase;letter-spacing:.08em">Tenure</div><div style="font-family:var(--font-display);font-size:1.7rem;color:var(--navy-800);font-weight:600">7–40 days</div></div>
          <div style="background:#fff;border:1px solid var(--line);border-radius:18px;padding:22px"><div style="font-size:.8rem;color:var(--muted);font-weight:700;text-transform:uppercase;letter-spacing:.08em">From</div><div style="font-family:var(--font-display);font-size:1.7rem;color:var(--navy-800);font-weight:600">1% / day</div></div>
          <div style="background:var(--grad-navy);border-radius:18px;padding:22px;color:#fff"><div style="font-size:.8rem;color:#9fb3cc;font-weight:700;text-transform:uppercase;letter-spacing:.08em">Charges</div><div style="font-family:var(--font-display);font-size:1.7rem;color:var(--gold-400);font-weight:600">No advance fee</div></div>
        </div>
      </div>
    </div>
  </div></div>

  <!-- USE CASES -->
  <div class="sec" style="padding-top:0"><div class="wrap">
    <div class="sec-head"><span class="eyebrow center reveal">A loan for every moment</span><h2 class="reveal d1">Whatever life asks for, we're ready</h2></div>
    <div class="uses">
      <div class="use reveal"><div class="uico"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 7H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-6a2 2 0 0 0-2-2H6"></path><path d="M16 14h.01"></path></svg></div><div class="uamt">₹8,000</div><h4>Fuel &amp; daily expenses</h4><p>Bridge the gap before payday.</p></div>
      <div class="use reveal d1"><div class="uico"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 10 12 5 2 10l10 5 10-5z"></path><path d="M6 12v5c0 1 2.7 3 6 3s6-2 6-3v-5"></path></svg></div><div class="uamt">₹18,000</div><h4>School &amp; college fees</h4><p>Never let a deadline wait.</p></div>
      <div class="use reveal d2"><div class="uico"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="4"></rect><path d="M12 9v6M9 12h6"></path></svg></div><div class="uamt">₹25,000</div><h4>Medical emergency</h4><p>Care now, repay comfortably.</p></div>
      <div class="use reveal d3"><div class="uico"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11l9-7 9 7"></path><path d="M5 10v10h14V10"></path><path d="M10 20v-6h4v6"></path></svg></div><div class="uamt">₹50,000</div><h4>Home &amp; repairs</h4><p>Fix what matters, fast.</p></div>
    </div>
  </div></div>

  <!-- FAQ PREVIEW -->
  <div class="sec" style="background:var(--grad-cream)"><div class="wrap">
    <div class="sec-head"><span class="eyebrow center reveal">Good to know</span><h2 class="reveal d1">Frequently asked questions</h2></div>
    <div class="faq reveal d1">
      <div class="qa"><button class="q">How quickly can I get a loan from NAVIX?<span class="qi">+</span></button><div class="a"><p>The whole journey is fully online. Once your agreement is e-signed and your KYC is complete, your funds are released to your bank account.</p></div></div>
      <div class="qa"><button class="q">What documents do I need to apply?<span class="qi">+</span></button><div class="a"><p>Typically just your PAN, Aadhaar (for KYC) and bank account details. Everything is verified digitally — no physical paperwork or branch visits.</p></div></div>
      <div class="qa"><button class="q">Are there any hidden charges or advance fees?<span class="qi">+</span></button><div class="a"><p>Never. NAVIX does not charge any advance or upfront fee. All applicable interest and charges are shown in your loan summary before you accept the offer.</p></div></div>
      <div class="qa"><button class="q">Will checking my eligibility affect my credit score?<span class="qi">+</span></button><div class="a"><p>No. Checking your eligibility on NAVIX does not impact your credit score — a formal credit enquiry only happens if you choose to accept an offer.</p></div></div>
    </div>
    <div style="text-align:center;margin-top:40px" class="reveal d1"><a href="/faq" class="btn btn-navy" data-link="">View all FAQs</a></div>
  </div></div>

<style>
/* ===== repayment calendar ===== */
.cal-card{background:var(--paper);border:1px solid var(--line);border-radius:var(--r-xl);box-shadow:var(--shadow-md);padding:clamp(22px,3vw,34px);display:grid;grid-template-columns:1.25fr .9fr;gap:clamp(24px,3vw,38px);align-items:stretch}
.cal-main{min-width:0}
.cal-divide{height:1px;background:var(--line);margin:4px 0 24px}
.cal-preset{padding:7px 15px;border-radius:var(--pill);border:1px solid var(--line-2);background:#fff;font-size:.82rem;font-weight:600;color:var(--slate);cursor:pointer;transition:.2s}
.cal-preset:hover,.cal-preset.on{background:var(--navy-800);color:#fff;border-color:var(--navy-800)}
.cal-sublabel{font-family:var(--font-mono);font-size:.66rem;letter-spacing:.08em;text-transform:uppercase;color:var(--gold-700);font-weight:500;margin-bottom:5px}
.cal-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}
.cal-title{font-family:var(--font-display);font-size:clamp(1.2rem,2.3vw,1.5rem);font-weight:700;color:var(--navy-800);letter-spacing:-.02em;line-height:1}
.cal-nav{display:flex;align-items:center;gap:8px}
.cal-iconbtn{width:40px;height:40px;border-radius:11px;border:1px solid var(--line);background:var(--paper);color:var(--navy-700);display:grid;place-items:center;cursor:pointer;transition:background .2s,border-color .2s,color .2s}
.cal-iconbtn:hover{background:var(--cream-100);border-color:var(--line-2);color:var(--navy-900)}
.cal-iconbtn:disabled{opacity:.32;pointer-events:none}
.cal-dows{display:grid;grid-template-columns:repeat(7,1fr);gap:6px;margin-bottom:8px}
.cal-dows span{font-family:var(--font-mono);font-size:.66rem;letter-spacing:.06em;text-transform:uppercase;color:var(--muted);text-align:center;font-weight:500}
.cal-grid{display:grid;grid-template-columns:repeat(7,1fr);gap:6px;grid-auto-rows:clamp(42px,5.4vw,50px)}
.cal-day{display:grid;place-items:center;border:1px solid transparent;border-radius:12px;background:transparent;font-family:var(--font-display);font-weight:600;font-size:1.02rem;color:var(--navy-800);cursor:pointer;transition:background .18s,color .18s,box-shadow .18s;font-feature-settings:"tnum";-webkit-tap-highlight-color:transparent}
.cal-day:hover{background:var(--cream-100)}
.cal-day.muted{color:var(--muted);opacity:.4;cursor:default;pointer-events:none}
.cal-day.today{box-shadow:inset 0 0 0 1.5px var(--gold-500);color:var(--gold-700)}
.cal-day.sel{background:var(--grad-navy);color:#fff;box-shadow:var(--shadow-sm)}
.cal-day.sel.today{color:#fff;box-shadow:var(--shadow-sm),inset 0 0 0 1.5px var(--gold-400)}
.cal-legend{display:flex;align-items:center;gap:18px;margin-top:18px;font-size:.78rem;color:var(--slate);flex-wrap:wrap}
.cal-legend span{display:inline-flex;align-items:center;gap:8px}
.cal-legend i{width:16px;height:16px;border-radius:6px;display:inline-block}
.cal-legend .lg-sel{background:var(--grad-navy)}
.cal-hintnote{color:var(--muted)}
.cal-side{position:relative;overflow:hidden;background:var(--grad-navy);border-radius:var(--r-lg);padding:clamp(22px,2.6vw,28px);display:flex;flex-direction:column;color:#fff}
.cal-side::before{content:"";position:absolute;top:-40%;right:-30%;width:75%;height:75%;background:radial-gradient(circle,rgba(244,201,91,.20),transparent 70%);pointer-events:none}
.cal-side > *{position:relative}
.cal-side .eyebrow{color:var(--gold-400)}
.cal-side .eyebrow::before{background:var(--gold-400)}
.cal-bigdate{font-family:var(--font-display);font-weight:800;font-size:clamp(2.4rem,5.4vw,3.1rem);line-height:1;letter-spacing:-.03em;margin-top:14px}
.cal-subdate{color:#9fb3cc;font-size:.92rem;margin-top:7px}
.cal-note{font-size:.8rem;color:#9fb3cc;margin:18px 0 0;line-height:1.55}
.cal-side .btn{margin-top:auto}
@media(max-width:840px){.cal-card{grid-template-columns:1fr}}
</style>

  <!-- REPAYMENT CALENDAR -->
  <div class="sec" id="s-calendar"><div class="wrap">
    <div class="sec-head"><span class="eyebrow center reveal">Plan ahead</span><h2 class="reveal d1">Mark your repayment date</h2><p class="reveal d2">Set your amount, pick a repayment date, and see your exact interest, APR and total payable — calculated at a fixed 1% per day, the same way our calculator works.</p></div>
    <div class="cal-card reveal d1">
      <div class="cal-main">
        <div class="cc-row">
          <div class="cc-top"><span class="cl"><span class="ci"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v10M9.5 9.5h4a1.5 1.5 0 0 1 0 3h-3.5a1.5 1.5 0 0 0 0 3H15"></path></svg></span> Loan amount</span><span class="cc-val" id="calAmtV">₹10,000</span></div>
          <input type="range" id="calAmt" min="5000" max="1000000" step="5000" value="10000">
          <div class="cc-scale"><span>₹5,000</span><span>₹10,00,000</span></div>
          <div class="cc-presets"><button class="cal-preset" data-v="5000" type="button">₹5K</button><button class="cal-preset on" data-v="10000" type="button">₹10K</button><button class="cal-preset" data-v="100000" type="button">₹1L</button><button class="cal-preset" data-v="500000" type="button">₹5L</button><button class="cal-preset" data-v="1000000" type="button">₹10L</button></div>
        </div>
        <div class="cal-divide"></div>
        <div class="cal-head">
          <div><div class="cal-sublabel">Repayment date</div><div class="cal-title" id="calTitle">Month YYYY</div></div>
          <div class="cal-nav">
            <button id="calPrev" class="cal-iconbtn" type="button" aria-label="Previous month"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M15 18l-6-6 6-6"></path></svg></button>
            <button id="calNext" class="cal-iconbtn" type="button" aria-label="Next month"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M9 6l6 6-6 6"></path></svg></button>
          </div>
        </div>
        <div class="cal-dows"><span>Sun</span><span>Mon</span><span>Tue</span><span>Wed</span><span>Thu</span><span>Fri</span><span>Sat</span></div>
        <div class="cal-grid" id="calGrid"></div>
        <div class="cal-legend"><span><i class="lg-sel"></i> Selected repayment date</span><span class="cal-hintnote">Choose a date 7–40 days from today</span></div>
      </div>
      <div class="cal-side">
        <span class="eyebrow">Your repayment plan</span>
        <div class="cal-bigdate" id="calBig">—</div>
        <div class="cal-subdate" id="calSub">—</div>
        <div class="cr-rows" style="margin-top:24px">
          <div class="cr-line"><span>Loan amount</span><b id="calOA">₹10,000</b></div>
          <div class="cr-line"><span>Tenure</span><b id="calOT">30 days</b></div>
          <div class="cr-line"><span>Interest rate</span><b id="calOR">1% / day</b></div>
          <div class="cr-line"><span>Interest payable</span><b id="calOI">₹3,000</b></div>
          <div class="cr-line apr"><span>Representative APR</span><b id="calOApr">365.0%</b></div>
          <div class="cr-total"><span class="t-lbl">Total payable</span><span class="t-val" id="calOTotal">₹13,000</span></div>
        </div>
        <p class="cal-note">Interest is a fixed 1% per day across your selected tenure. Figures are indicative — your exact due date and APR are confirmed before you accept, with no pre-closure or prepayment charge.</p>
        <a href="/signup/mobile-otp" class="btn btn-gold btn-block" data-link="">Apply for this loan <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14M12 5l7 7-7 7"></path></svg></a>
      </div>
    </div>
  </div></div>

  <!-- FINAL CTA -->
  <div class="sec"><div class="wrap"><div class="ctaband reveal">
    <h2>Ready when you are</h2>
    <p>Check your eligibility in minutes. It's free, it won't affect your credit score, and there's never an advance fee.</p>
    <div class="hero-cta">
      <a href="/signup/mobile-otp" class="btn btn-gold btn-lg" data-link="">Apply Now <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14M12 5l7 7-7 7"></path></svg></a>
      <a href="/calculator" class="btn btn-outline-light btn-lg" data-link="">Try the calculator</a>
    </div>
  </div></div></div>

</section>

<!-- ==================== CALCULATOR & RATES ==================== -->
`;
