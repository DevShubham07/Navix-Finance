// AUTO-GENERATED from the NAVIX design export — do not hand-edit.
// Source section id: #calculator. Regenerate via transform_html.py.
/* eslint-disable */
export const html = `<section class="page active" id="calculator">
  <div class="page-hero"><div class="wrap">
    <div class="crumb"><a href="/" data-link="">Home</a> &nbsp;/&nbsp; Calculator &amp; Rates</div>
    <h1>Loan calculator &amp; transparent rates</h1>
    <p>Adjust your amount and tenure to instantly see your interest, APR and total payable. What you see here is what you'll repay — no hidden charges, no advance fees.</p>
  </div></div>

  <div class="sec" style="padding-top:64px"><div class="wrap">
    <div class="calc-wrap">
      <!-- CONTROLS -->
      <div class="calc-controls reveal">
        <div class="cc-row">
          <div class="cc-top"><span class="cl"><span class="ci"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v10M9.5 9.5h4a1.5 1.5 0 0 1 0 3h-3.5a1.5 1.5 0 0 0 0 3H15"></path></svg></span> Loan amount</span><span class="cc-val" id="amtV">₹10,000</span></div>
          <input type="range" id="amt" min="5000" max="1000000" step="5000" value="10000">
          <div class="cc-scale"><span>₹5,000</span><span>₹10,00,000</span></div>
          <div class="cc-presets"><button class="preset" data-v="5000">₹5K</button><button class="preset on" data-v="10000">₹10K</button><button class="preset" data-v="100000">₹1L</button><button class="preset" data-v="500000">₹5L</button><button class="preset" data-v="1000000">₹10L</button></div>
        </div>
        <div class="cc-row">
          <div class="cc-top"><span class="cl"><span class="ci"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2"></rect><path d="M16 2v4M8 2v4M3 10h18"></path></svg></span> Tenure</span><span class="cc-val" id="tenV">30 days</span></div>
          <input type="range" id="ten" min="7" max="40" step="1" value="30">
          <div class="cc-scale"><span>7 days</span><span>40 days</span></div>
        </div>
        <div class="cc-row">
          <div class="cc-top"><span class="cl"><span class="ci"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 17l6-6 4 4 8-8"></path><path d="M21 7v5h-5"></path></svg></span> Daily interest rate</span><span class="cc-val" id="rateV">1% / day</span></div>
          <input type="range" id="rate" min="0.5" max="1.5" step="0.1" value="1">
          <div class="cc-scale"><span>0.5% / day</span><span>1.5% / day</span></div>
          <p style="font-size:.8rem;color:var(--muted);margin-top:10px">Indicative only. Your final rate is set by the partner NBFC based on your profile and disclosed in the Key Fact Statement.</p>
        </div>
      </div>
      <!-- RESULT -->
      <div class="calc-result reveal d1">
        <div class="donut-wrap">
          <svg width="170" height="170" class="donut" viewBox="0 0 130 130">
            <circle cx="65" cy="65" r="52" fill="none" stroke="rgba(255,255,255,.12)" stroke-width="15"></circle>
            <circle id="ringPrin" cx="65" cy="65" r="52" fill="none" stroke="#3FBF89" stroke-width="15" stroke-linecap="round" stroke-dasharray="0 327"></circle>
            <circle id="ringInt" cx="65" cy="65" r="52" fill="none" stroke="#2C6298" stroke-width="15" stroke-linecap="round" stroke-dasharray="0 327"></circle>
          </svg>
          <div class="donut-center"><div class="dc-amt" id="dcAmt">₹13,000</div><div class="dc-lbl">Total payable</div></div>
        </div>
        <div class="cr-rows">
          <div class="cr-line"><span>Loan amount</span><b id="oAmt">₹10,000</b></div>
          <div class="cr-line"><span>Tenure</span><b id="oTen">30 days</b></div>
          <div class="cr-line"><span>Interest rate</span><b id="oRate">1%</b></div>
          <div class="cr-line"><span>Interest payable</span><b id="oInt">₹3,000</b></div>
          <div class="cr-line apr"><span>Representative APR</span><b id="oApr">365.0%</b></div>
          <div class="cr-total"><span class="t-lbl">Total payable</span><span class="t-val" id="oTotal">₹13,000</span></div>
        </div>
        <div class="legend"><span><span class="sw" style="background:#3FBF89"></span> Principal</span><span><span class="sw" style="background:#2C6298"></span> Interest</span></div>
      </div>
    </div>
  </div></div>

  <!-- REPAYMENT CALENDAR -->
  <div class="sec" style="padding-top:0"><div class="wrap">
    <div class="sec-head left" style="max-width:760px"><span class="eyebrow">Plan ahead</span><h2>Mark your repayment date</h2><p>Set your amount, pick the day you're next paid, and see your exact interest, APR and total payable — calculated at a fixed 1% per day, the same way our calculator works.</p></div>
    <div class="cal-card reveal">
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

  <!-- RATE TABLE -->
  <div class="sec" style="padding-top:0"><div class="wrap">
    <div class="sec-head left" style="max-width:760px"><span class="eyebrow">Transparent rate table</span><h2>See the cost for every plan</h2><p>Pick an amount to view interest and total payable across tenures. The row matching your selected tenure above is highlighted automatically.</p></div>
    <div class="ratetable reveal">
      <div class="rt-tabs">
        <button class="rt-tab" data-amt="5000">₹5,000</button>
        <button class="rt-tab on" data-amt="10000">₹10,000</button>
        <button class="rt-tab" data-amt="25000">₹25,000</button>
        <button class="rt-tab" data-amt="50000">₹50,000</button>
        <button class="rt-tab" data-amt="1000000">₹10,00,000</button>
      </div>
      <table class="rt"><thead><tr><th>Tenure</th><th>Interest @ 1%/day</th><th>Total payable</th><th>Interest share</th><th>Rep. APR</th></tr></thead><tbody>
        <!-- 5000 -->
        <tr data-amt="5000" data-lo="7" data-hi="10" style="display:none"><td><b>7 days</b></td><td>₹350</td><td><b>₹5,350</b></td><td><span class="rt-bar"><i style="width:7%"></i></span></td><td>365%</td></tr>
        <tr data-amt="5000" data-lo="11" data-hi="20" style="display:none"><td><b>15 days</b></td><td>₹750</td><td><b>₹5,750</b></td><td><span class="rt-bar"><i style="width:13%"></i></span></td><td>365%</td></tr>
        <tr data-amt="5000" data-lo="21" data-hi="35" style="display:none"><td><b>30 days</b></td><td>₹1,500</td><td><b>₹6,500</b></td><td><span class="rt-bar"><i style="width:23%"></i></span></td><td>365%</td></tr>
        <tr data-amt="5000" data-lo="36" data-hi="40" style="display:none"><td><b>40 days</b></td><td>₹2,000</td><td><b>₹7,000</b></td><td><span class="rt-bar"><i style="width:29%"></i></span></td><td>365%</td></tr>
        <!-- 10000 -->
        <tr data-amt="10000" data-lo="7" data-hi="10"><td><b>7 days</b></td><td>₹700</td><td><b>₹10,700</b></td><td><span class="rt-bar"><i style="width:7%"></i></span></td><td>365%</td></tr>
        <tr data-amt="10000" data-lo="11" data-hi="20"><td><b>15 days</b></td><td>₹1,500</td><td><b>₹11,500</b></td><td><span class="rt-bar"><i style="width:13%"></i></span></td><td>365%</td></tr>
        <tr data-amt="10000" data-lo="21" data-hi="35"><td><b>30 days</b></td><td>₹3,000</td><td><b>₹13,000</b></td><td><span class="rt-bar"><i style="width:23%"></i></span></td><td>365%</td></tr>
        <tr data-amt="10000" data-lo="36" data-hi="40"><td><b>40 days</b></td><td>₹4,000</td><td><b>₹14,000</b></td><td><span class="rt-bar"><i style="width:29%"></i></span></td><td>365%</td></tr>
        <!-- 25000 -->
        <tr data-amt="25000" data-lo="7" data-hi="10" style="display:none"><td><b>7 days</b></td><td>₹1,750</td><td><b>₹26,750</b></td><td><span class="rt-bar"><i style="width:7%"></i></span></td><td>365%</td></tr>
        <tr data-amt="25000" data-lo="11" data-hi="20" style="display:none"><td><b>15 days</b></td><td>₹3,750</td><td><b>₹28,750</b></td><td><span class="rt-bar"><i style="width:13%"></i></span></td><td>365%</td></tr>
        <tr data-amt="25000" data-lo="21" data-hi="35" style="display:none"><td><b>30 days</b></td><td>₹7,500</td><td><b>₹32,500</b></td><td><span class="rt-bar"><i style="width:23%"></i></span></td><td>365%</td></tr>
        <tr data-amt="25000" data-lo="36" data-hi="40" style="display:none"><td><b>40 days</b></td><td>₹10,000</td><td><b>₹35,000</b></td><td><span class="rt-bar"><i style="width:29%"></i></span></td><td>365%</td></tr>
        <!-- 50000 -->
        <tr data-amt="50000" data-lo="7" data-hi="10" style="display:none"><td><b>7 days</b></td><td>₹3,500</td><td><b>₹53,500</b></td><td><span class="rt-bar"><i style="width:7%"></i></span></td><td>365%</td></tr>
        <tr data-amt="50000" data-lo="11" data-hi="20" style="display:none"><td><b>15 days</b></td><td>₹7,500</td><td><b>₹57,500</b></td><td><span class="rt-bar"><i style="width:13%"></i></span></td><td>365%</td></tr>
        <tr data-amt="50000" data-lo="21" data-hi="35" style="display:none"><td><b>30 days</b></td><td>₹15,000</td><td><b>₹65,000</b></td><td><span class="rt-bar"><i style="width:23%"></i></span></td><td>365%</td></tr>
        <tr data-amt="50000" data-lo="36" data-hi="40" style="display:none"><td><b>40 days</b></td><td>₹20,000</td><td><b>₹70,000</b></td><td><span class="rt-bar"><i style="width:29%"></i></span></td><td>365%</td></tr>
        <!-- 1000000 -->
        <tr data-amt="1000000" data-lo="7" data-hi="10" style="display:none"><td><b>7 days</b></td><td>₹70,000</td><td><b>₹10,70,000</b></td><td><span class="rt-bar"><i style="width:7%"></i></span></td><td>365%</td></tr>
        <tr data-amt="1000000" data-lo="11" data-hi="20" style="display:none"><td><b>15 days</b></td><td>₹1,50,000</td><td><b>₹11,50,000</b></td><td><span class="rt-bar"><i style="width:13%"></i></span></td><td>365%</td></tr>
        <tr data-amt="1000000" data-lo="21" data-hi="35" style="display:none"><td><b>30 days</b></td><td>₹3,00,000</td><td><b>₹13,00,000</b></td><td><span class="rt-bar"><i style="width:23%"></i></span></td><td>365%</td></tr>
        <tr data-amt="1000000" data-lo="36" data-hi="40" style="display:none"><td><b>40 days</b></td><td>₹4,00,000</td><td><b>₹14,00,000</b></td><td><span class="rt-bar"><i style="width:29%"></i></span></td><td>365%</td></tr>
      </tbody></table>
      <div class="rt-note">Illustrative figures at 1%/day. Actual rate, processing fee and APR are determined by the partner NBFC and disclosed in your Key Fact Statement before acceptance.</div>
    </div>
  </div></div>

  <!-- RATES & CHARGES + REP EXAMPLE -->
  <div class="sec" style="padding-top:0"><div class="wrap"><div class="elig">
    <div class="reveal">
      <span class="eyebrow">Rates &amp; charges</span>
      <h2 style="font-size:clamp(1.7rem,3.4vw,2.3rem);margin:14px 0 22px">Clear, upfront, and fair</h2>
      <div class="checklist">
        <div class="chk"><span class="ck" style="background:rgba(20,160,107,.14);color:var(--gold-600)"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M20 6 9 17l-5-5"></path></svg></span><div><b>Loan amount: ₹5,000 to ₹10,00,000</b><small>Borrow exactly what you need.</small></div></div>
        <div class="chk"><span class="ck" style="background:rgba(20,160,107,.14);color:var(--gold-600)"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M20 6 9 17l-5-5"></path></svg></span><div><b>Tenure: 7 to 40 days</b><small>Short-term, near-term repayment.</small></div></div>
        <div class="chk"><span class="ck" style="background:rgba(20,160,107,.14);color:var(--gold-600)"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M20 6 9 17l-5-5"></path></svg></span><div><b>No pre-closure or prepayment charges</b><small>Clear early and pay less — always.</small></div></div>
        <div class="chk"><span class="ck" style="background:rgba(20,160,107,.14);color:var(--gold-600)"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M20 6 9 17l-5-5"></path></svg></span><div><b>No advance fees, ever</b><small>We never ask for upfront payments.</small></div></div>
        <div class="chk"><span class="ck" style="background:rgba(20,160,107,.14);color:var(--gold-600)"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M20 6 9 17l-5-5"></path></svg></span><div><b>Net monthly salary ≥ ₹40,000</b><small>A core eligibility criterion.</small></div></div>
      </div>
    </div>
    <div class="reveal d1">
      <div class="calc-result" style="position:sticky;top:110px">
        <span class="eyebrow" style="color:var(--gold-400)">Representative example</span>
        <h3 style="color:#fff;font-size:1.4rem;margin:14px 0 18px;position:relative">A ₹10,000 loan, simply explained</h3>
        <div class="cr-rows">
          <div class="cr-line"><span>You borrow</span><b>₹10,000</b></div>
          <div class="cr-line"><span>For a tenure of</span><b>30 days</b></div>
          <div class="cr-line"><span>Daily interest @ 1%</span><b>₹3,000</b></div>
          <div class="cr-line apr"><span>Representative APR</span><b>365.0%</b></div>
          <div class="cr-total"><span class="t-lbl">You repay</span><span class="t-val">₹13,000</span></div>
        </div>
        <a href="/signup/mobile-otp" class="btn btn-gold btn-block" style="margin-top:22px" data-link="">Apply for this loan <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M5 12h14M12 5l7 7-7 7"></path></svg></a>
        <p style="font-size:.78rem;color:#9fb3cc;margin-top:14px;text-align:center;position:relative">Borrow responsibly. APR shown for illustration.</p>
      </div>
    </div>
  </div></div></div>
</section>`;
