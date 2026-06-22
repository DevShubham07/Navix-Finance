"use client";

import * as React from "react";
import Link from "next/link";
import { formatINR0 } from "@/lib/utils";

/** Standard reducing-balance EMI for the public marketing calculator. */
function computeEmi(principal: number, annualRatePct: number, months: number) {
  const r = annualRatePct / 12 / 100;
  if (r === 0) return { emi: principal / months, total: principal, interest: 0 };
  const pow = Math.pow(1 + r, months);
  const emi = (principal * r * pow) / (pow - 1);
  const total = emi * months;
  return { emi, total, interest: total - principal };
}

function Slider({
  label,
  value,
  display,
  sub,
  min,
  max,
  step,
  onChange,
}: {
  label: string;
  value: number;
  display: string;
  sub: string;
  min: number;
  max: number;
  step: number;
  onChange: (v: number) => void;
}) {
  return (
    <div className="slider-field">
      <div className="sf-top">
        <span className="sf-label">{label}</span>
        <span className="sf-value">{display}</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        aria-label={label}
        onChange={(e) => onChange(Number(e.target.value))}
      />
      <div className="sf-sub">{sub}</div>
    </div>
  );
}

export function EmiCalculator() {
  const [amount, setAmount] = React.useState(200000);
  const [rate, setRate] = React.useState(14);
  const [tenure, setTenure] = React.useState(24);

  const { emi, total, interest } = computeEmi(amount, rate, tenure);

  return (
    <div className="calc" id="emi-calc">
      <div className="calc-inputs">
        <Slider
          label="Loan amount"
          value={amount}
          display={formatINR0(amount)}
          sub="₹25,000 – ₹10,00,000"
          min={25000}
          max={1000000}
          step={5000}
          onChange={setAmount}
        />
        <Slider
          label="Interest rate"
          value={rate}
          display={`${rate.toFixed(1)}% p.a.`}
          sub="Indicative — actual rate set by the lending partner"
          min={10}
          max={28}
          step={0.5}
          onChange={setRate}
        />
        <Slider
          label="Tenure"
          value={tenure}
          display={`${tenure} months`}
          sub="3 – 60 months"
          min={3}
          max={60}
          step={3}
          onChange={setTenure}
        />
        <div className="calc-disclaimer">
          Indicative calculation only. Final loan amount, interest rate, fees and tenure are
          determined by the lending partner based on its credit policy and your eligibility.
        </div>
      </div>
      <div className="calc-output">
        <h3>Your estimate</h3>
        <div className="calc-result-row highlight">
          <span className="crr-label">Monthly EMI</span>
          <span className="crr-val">{formatINR0(emi)}</span>
        </div>
        <div className="calc-result-row" style={{ marginTop: 14 }}>
          <span className="crr-label">Principal amount</span>
          <span className="crr-val">{formatINR0(amount)}</span>
        </div>
        <div className="calc-result-row">
          <span className="crr-label">Total interest</span>
          <span className="crr-val">{formatINR0(interest)}</span>
        </div>
        <div className="calc-result-row highlight">
          <span className="crr-label">Total payable</span>
          <span className="crr-val">{formatINR0(total)}</span>
        </div>
        <Link href="/signup/pan" className="btn btn-gold btn-block mt-3">
          Apply for this loan
        </Link>
      </div>
    </div>
  );
}
