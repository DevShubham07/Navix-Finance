"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { FileSignature, Loader2, ShieldCheck } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { offerApi, type StepResult } from "@/lib/api/applications";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { formatApiError } from "@/lib/api/errors";

/**
 * Screen 9: eSign the sanction letter.
 *
 * <p>The provider behind this is currently a mock (`MockEsignAdapter`) that signs immediately, so
 * there is no redirect to wait on. The page is written for the real flow anyway — one action, one
 * result — so swapping the adapter in changes nothing here.
 *
 * <p>Unlike the identity checks, this one really does gate: the backend refuses to move the
 * application to disbursement without a signature, so a failure here stops rather than passes
 * through.
 */
export default function EsignPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();
  const canvasRef = React.useRef<HTMLCanvasElement>(null);
  const drawing = React.useRef(false);

  const canvasPoint = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    return { x: (event.clientX - rect.left) * (canvas.width / rect.width), y: (event.clientY - rect.top) * (canvas.height / rect.height) };
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
        navigator.geolocation.getCurrentPosition(resolve, () => resolve(null), { enableHighAccuracy: true, timeout: 7000 });
      });
      const r = await offerApi.esign(appId, {
        signatureDataUrl: canvas.toDataURL("image/png"),
        latitude: location?.coords.latitude,
        longitude: location?.coords.longitude,
        accuracyMeters: location?.coords.accuracy,
      });
      setResult(r);
      if (r.status === "PASS") {
        await completeOfferStep(appId, "OFFER_ESIGN", router, nextOfferRoute("esign"));
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
      <div className="form-card text-center">
        <span className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-full bg-navy text-white">
          <FileSignature size={32} />
        </span>
        <h1 className="text-2xl">Sign your agreement</h1>
        <p className="mx-auto mb-6 max-w-md text-muted">
          Signing confirms you accept the Key Fact Statement you just read — the amount, the charges,
          the repayment date and the total repayable.
        </p>

        <ul className="mx-auto mb-6 max-w-sm space-y-2 text-left text-sm">
          {[
            "Your drawn signature and consent are saved",
            "A signed copy is saved to your documents",
            "Nothing is disbursed until you confirm your bank account",
          ].map((t) => (
            <li key={t} className="flex items-center gap-2 text-ink">
              <ShieldCheck size={15} className="text-success-600" /> {t}
            </li>
          ))}
        </ul>

        <div className="hidden">
          {busy ? <Loader2 size={16} className="animate-spin" /> : <FileSignature size={16} />}
          {busy ? "Signing…" : "Sign now"}
        </div>

        <p className="mt-6 text-left text-sm text-muted">Use your pointer, mouse, or touchscreen to sign inside this box.</p>
        <canvas
          ref={canvasRef}
          width={720}
          height={220}
          className="mt-2 h-44 w-full touch-none rounded-lg border-2 border-dashed border-navy/40 bg-white"
          onPointerDown={beginDrawing}
          onPointerMove={draw}
          onPointerUp={() => { drawing.current = false; }}
          onPointerCancel={() => { drawing.current = false; }}
        />
        <div className="mt-4 flex flex-wrap justify-center gap-3">
          <button type="button" onClick={clearSignature} disabled={busy} className="btn btn-outline">
            Clear signature
          </button>
          <button onClick={sign} disabled={busy} className="btn btn-gold">
            {busy ? <Loader2 size={16} className="animate-spin" /> : <FileSignature size={16} />}
            {busy ? "Saving signature..." : "Save signature"}
          </button>
        </div>

        <StepResultBanner result={result} />
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <div className="mt-8">
        <a href={prevOfferRoute("esign")} className="btn btn-outline btn-sm">Back</a>
      </div>
      <Reassurance />
    </div>
  );
}
