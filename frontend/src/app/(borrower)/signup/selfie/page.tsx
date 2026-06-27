"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Camera, Loader2, RefreshCw, ArrowRight } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding } from "@/lib/onboarding";
import { verificationApi, ApplicationApiError, type StepResult } from "@/lib/api/applications";

type Phase = "idle" | "live" | "uploading" | "done";

export default function SignupSelfiePage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const videoRef = React.useRef<HTMLVideoElement>(null);
  const streamRef = React.useRef<MediaStream | null>(null);
  const [phase, setPhase] = React.useState<Phase>("idle");
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [failCount, setFailCount] = React.useState(0);
  const [error, setError] = React.useState<string>();

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const stopCamera = React.useCallback(() => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
  }, []);

  React.useEffect(() => () => stopCamera(), [stopCamera]);

  const startCamera = async () => {
    setError(undefined);
    setResult(null);
    if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
      setError("Your device or browser doesn't support camera capture.");
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "user" }, audio: false });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play().catch(() => {});
      }
      setPhase("live");
    } catch {
      setError("We couldn't access your camera. Please allow camera access and try again.");
    }
  };

  const capture = async () => {
    if (appId == null) return;
    const video = videoRef.current;
    if (!video) return;
    const w = video.videoWidth || 480;
    const h = video.videoHeight || 480;
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) { setError("Could not capture the image — please try again."); return; }
    ctx.drawImage(video, 0, 0, w, h);
    const blob: Blob | null = await new Promise((resolve) => canvas.toBlob((b) => resolve(b), "image/jpeg", 0.9));
    if (!blob) { setError("Could not capture the image — please try again."); return; }

    setPhase("uploading");
    setError(undefined);
    try {
      const { key, url } = await verificationApi.presignUpload(appId, { docType: "SELFIE", fileName: "selfie.jpg", contentType: "image/jpeg" });
      await verificationApi.putToPresignedUrl(url, blob, "image/jpeg");
      const r = await verificationApi.selfie(appId, key);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") {
        stopCamera();
        setPhase("done");
        setTimeout(() => router.push("/signup/agreement"), 700);
        return;
      }
      // FAIL: allow exactly one retry, then proceed for manual review.
      if (failCount >= 1) {
        stopCamera();
        setPhase("done");
        setTimeout(() => router.push("/signup/agreement"), 900);
      } else {
        setFailCount((n) => n + 1);
        setPhase("live");
      }
    } catch (err) {
      setError(err instanceof ApplicationApiError ? `${err.message} (${err.code})` : "Could not verify your selfie — please try again.");
      setPhase("live");
    }
  };

  return (
    <div>
      <div className="form-card text-center">
        <h1 className="text-2xl">Take a quick selfie</h1>
        <p className="mx-auto mb-6 max-w-md text-muted">
          We match your face to your PAN photo. Look straight at the camera in good lighting.
        </p>

        <div className="mx-auto mb-6 grid aspect-square w-full max-w-xs place-items-center overflow-hidden rounded-full border-4 border-dashed border-line bg-grey-100">
          {/* video is always mounted so the ref is available before play() */}
          <video
            ref={videoRef}
            playsInline
            muted
            className={`h-full w-full object-cover ${phase === "live" || phase === "uploading" ? "" : "hidden"}`}
          />
          {phase === "idle" ? <Camera size={64} className="text-muted" /> : null}
          {phase === "done" ? <ArrowRight size={56} className="text-success-600" /> : null}
        </div>

        {phase === "idle" ? (
          <button onClick={startCamera} className="btn btn-navy">
            <Camera size={16} /> Start camera
          </button>
        ) : phase === "live" ? (
          <button onClick={capture} className="btn btn-gold">
            {failCount > 0 ? <RefreshCw size={16} /> : null}
            {failCount > 0 ? "Retake selfie" : "Capture selfie"}
          </button>
        ) : phase === "uploading" ? (
          <div className="flex items-center justify-center gap-2 text-sm text-muted">
            <Loader2 size={16} className="animate-spin" /> Verifying your selfie…
          </div>
        ) : (
          <div className="flex items-center justify-center gap-2 text-sm font-semibold text-success-700">
            Continuing… <ArrowRight size={16} />
          </div>
        )}

        <StepResultBanner result={result} />
        {failCount > 0 && result?.status === "FAIL" ? (
          <p className="mt-2 text-xs text-muted">One more try — after this we&apos;ll send it for a quick manual review and you can continue.</p>
        ) : null}
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <div className="mt-8">
        <a href="/signup/penny-drop" className="btn btn-outline btn-sm" onClick={stopCamera}>Back</a>
      </div>
      <Reassurance />
    </div>
  );
}
