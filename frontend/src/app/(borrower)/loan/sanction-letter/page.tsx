"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, ExternalLink, FileSignature, FileText, Loader2, ShieldCheck } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { offerApi, type StepResult } from "@/lib/api/applications";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { formatApiError } from "@/lib/api/errors";

/**
 * Read and sign the exact agreement in one responsive page.
 *
 * Signing is Aadhaar e-sign: the borrower is redirected to the provider, completes an OTP against their
 * Aadhaar-registered mobile, and is returned to `/kyc/esign/callback`, which finalises. Drawing a
 * signature is the fallback for when that route isn't open to them — the provider is down, or the OTP
 * goes to a handset they no longer hold — so nobody is stranded at the one step that gates disbursement.
 */
export default function SanctionLetterPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [busy, setBusy] = React.useState(false);
  const [mode, setMode] = React.useState<"aadhaar" | "draw">("aadhaar");
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();
  const canvasRef = React.useRef<HTMLCanvasElement>(null);
  const drawing = React.useRef(false);

  const signWithAadhaar = async () => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      // A per-attempt nonce, for the same reason DigiLocker needs one: providers cache a signing
      // session against the redirect URL and re-serve a stale, expired one for a repeat URL.
      const nonce = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
      const base = `${window.location.origin}/kyc/esign/callback?app=${appId}&sid=${nonce}`;
      const r = await offerApi.esignInit(appId, {
        successRedirectUrl: `${base}&outcome=success`,
        failureRedirectUrl: `${base}&outcome=failure`,
      });
      if (r.derived?.fallback === true) {
        setMode("draw");
        setBusy(false);
        return;
      }
      const url = typeof r.derived?.url === "string" ? (r.derived.url as string) : null;
      if (!url) {
        setMode("draw");
        setBusy(false);
        return;
      }
      window.location.assign(url);
    } catch (err) {
      setError(formatApiError(err, "Could not start Aadhaar e-sign — you can sign below instead."));
      setMode("draw");
      setBusy(false);
    }
  };

  const q = useQuery({
    queryKey: ["sanction-letter", appId],
    queryFn: () => offerApi.sanctionLetter(appId as number),
    enabled: appId != null,
    gcTime: 0,
    staleTime: 0,
    retry: false,
  });

  const canvasPoint = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    return {
      x: (event.clientX - rect.left) * (canvas.width / rect.width),
      y: (event.clientY - rect.top) * (canvas.height / rect.height),
    };
  };

  const beginDrawing = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const point = canvasPoint(event);
    const context = canvasRef.current?.getContext("2d");
    if (!point || !context) return;
    drawing.current = true;
    canvasRef.current?.setPointerCapture(event.pointerId);
    context.strokeStyle = "#0C2540";
    context.lineWidth = 3;
    context.lineCap = "round";
    context.beginPath();
    context.moveTo(point.x, point.y);
  };

  const draw = (event: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawing.current) return;
    const point = canvasPoint(event);
    const context = canvasRef.current?.getContext("2d");
    if (!point || !context) return;
    context.lineTo(point.x, point.y);
    context.stroke();
  };

  const clearSignature = () => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    if (canvas && context) context.clearRect(0, 0, canvas.width, canvas.height);
  };

  const sign = async () => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      const canvas = canvasRef.current;
      if (!canvas) throw new Error("Signature area is unavailable");
      const pixels = canvas.getContext("2d")?.getImageData(0, 0, canvas.width, canvas.height).data;
      if (!pixels || !Array.from(pixels).some((value, index) => index % 4 === 3 && value > 0)) {
        throw new Error("Please draw your signature inside the box first");
      }
      const location = await new Promise<GeolocationPosition | null>((resolve) => {
        if (!navigator.geolocation) return resolve(null);
        navigator.geolocation.getCurrentPosition(resolve, () => resolve(null), {
          enableHighAccuracy: true,
          timeout: 7000,
        });
      });
      const response = await offerApi.esign(appId, {
        signatureDataUrl: canvas.toDataURL("image/png"),
        latitude: location?.coords.latitude,
        longitude: location?.coords.longitude,
        accuracyMeters: location?.coords.accuracy,
      });
      setResult(response);
      if (response.status === "PASS") {
        await completeOfferStep(
          appId,
          "OFFER_SANCTION_LETTER",
          router,
          nextOfferRoute("sanction-letter"),
        );
        return;
      }
      setBusy(false);
    } catch (err) {
      setError(formatApiError(err, "Could not sign your agreement — please try again."));
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">Your loan agreement</p>
        <p className="mb-5 text-sm text-muted">
          Read the Key Fact Statement and sign it directly below. The final saved PDF includes your
          signature, name, and the server-recorded date and time.
        </p>

        {q.isLoading ? (
          <div className="flex items-center justify-center gap-2 rounded border border-line bg-grey-100 py-16 text-sm text-muted">
            <Loader2 size={16} className="animate-spin" /> Preparing your agreement…
          </div>
        ) : q.error ? (
          <div className="flex items-start gap-2 rounded border border-error-100 bg-error-50 p-4 text-sm text-error-700">
            <AlertTriangle size={16} className="mt-0.5 flex-shrink-0" />
            <span>{formatApiError(q.error, "Could not prepare your agreement.")}</span>
          </div>
        ) : q.data ? (
          <>
            <iframe
              src={q.data.url}
              aria-label="Loan agreement"
              className="h-[32rem] w-full rounded border border-line bg-white"
            >
              <p className="p-6 text-sm text-muted">
                Your browser can&apos;t display the PDF here — open it using the link below.
              </p>
            </iframe>
            <a
              href={q.data.url}
              target="_blank"
              rel="noopener noreferrer"
              className="btn btn-outline mt-4 sm:btn-sm"
            >
              <FileText size={16} /> Open agreement <ExternalLink size={14} />
            </a>

            <div className="mt-8 border-t border-line pt-6 text-left">
              <h2 className="flex items-center gap-2 text-xl">
                <FileSignature size={21} /> Sign this agreement
              </h2>

              {mode === "aadhaar" ? (
                <>
                  <p className="mt-2 text-sm text-muted">
                    Sign with Aadhaar e-sign. You&apos;ll confirm with a one-time password sent to the
                    mobile number registered with your Aadhaar, then come straight back here.
                  </p>
                  <ul className="mt-4 grid gap-2 text-sm sm:grid-cols-2">
                    {[
                      "Legally valid Aadhaar e-signature",
                      "A signed copy is saved to your documents",
                    ].map((text) => (
                      <li key={text} className="flex items-center gap-2 text-ink">
                        <ShieldCheck size={15} className="text-success-600" /> {text}
                      </li>
                    ))}
                  </ul>
                  <div className="mt-5 flex flex-wrap items-center gap-3">
                    <button type="button" onClick={signWithAadhaar} disabled={busy} className="btn btn-gold">
                      {busy ? <Loader2 size={16} className="animate-spin" /> : <FileSignature size={16} />}
                      {busy ? "Opening Aadhaar e-sign…" : "Sign with Aadhaar"}
                    </button>
                    <button
                      type="button"
                      onClick={() => setMode("draw")}
                      disabled={busy}
                      className="btn btn-outline btn-sm"
                    >
                      Can&apos;t use Aadhaar? Sign by drawing
                    </button>
                  </div>
                  {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
                </>
              ) : (
                <>
                  <p className="mt-2 text-sm text-muted">
                    Use your pointer, mouse, or touchscreen to sign inside the box.
                  </p>
                  <ul className="mt-4 grid gap-2 text-sm sm:grid-cols-2">
                    {["Your drawn signature is saved", "A signed copy is saved to your documents"].map((text) => (
                      <li key={text} className="flex items-center gap-2 text-ink">
                        <ShieldCheck size={15} className="text-success-600" /> {text}
                      </li>
                    ))}
                  </ul>
                  <canvas
                    ref={canvasRef}
                    width={720}
                    height={220}
                    aria-label="Draw your signature"
                    className="mt-4 h-44 w-full touch-none rounded-lg border-2 border-dashed border-navy/40 bg-white"
                    onPointerDown={beginDrawing}
                    onPointerMove={draw}
                    onPointerUp={() => { drawing.current = false; }}
                    onPointerCancel={() => { drawing.current = false; }}
                  />
                  <div className="mt-4 flex flex-wrap gap-3">
                    <button type="button" onClick={clearSignature} disabled={busy} className="btn btn-outline">
                      Clear signature
                    </button>
                    <button type="button" onClick={sign} disabled={busy} className="btn btn-gold">
                      {busy ? <Loader2 size={16} className="animate-spin" /> : <FileSignature size={16} />}
                      {busy ? "Saving signature…" : "Sign and continue"}
                    </button>
                    <button
                      type="button"
                      onClick={() => { setMode("aadhaar"); setError(undefined); }}
                      disabled={busy}
                      className="btn btn-outline btn-sm"
                    >
                      Use Aadhaar e-sign instead
                    </button>
                  </div>
                  <StepResultBanner result={result} />
                  {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
                </>
              )}
            </div>
          </>
        ) : null}
      </div>

      <div className="mt-8">
        <a href={prevOfferRoute("sanction-letter")} className="btn btn-outline btn-sm">Back</a>
      </div>
      <Reassurance />
    </div>
  );
}
