"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Camera, Loader2, RefreshCw, ArrowRight, ScanFace } from "lucide-react";
import { Reassurance } from "@/components/borrower/reassurance";
import { StepResultBanner } from "@/components/borrower/step-result-banner";
import { useOnboarding, nextAfterStep } from "@/lib/onboarding";
import { verificationApi, type StepResult } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";

// Primary path = Signzy Liveness Secure: an in-page video journey (passive liveness + face-match
// against the DigiLocker Aadhaar photo), embedded in an iframe with `allow="camera"` — the provider's
// designed flow. We poll the backend for the result (our DB is authoritative, mirroring DigiLocker).
// Fallback = the legacy camera-capture selfie → Digitap face-match, used when Signzy liveness is
// unavailable (backend returns derived.fallback === true) or init fails.
type Phase =
  | "idle"
  | "starting"
  | "liveness"
  | "capture-idle"
  | "capture-live"
  | "capture-uploading"
  | "done";

const POLL_MS = 4000;
const POLL_LIMIT = 45; // ~3 min; then advance so the borrower is never stuck (drops to manual review).

export default function SignupSelfiePage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const [phase, setPhase] = React.useState<Phase>("idle");
  const [videoUrl, setVideoUrl] = React.useState<string | null>(null);
  const [result, setResult] = React.useState<StepResult | null>(null);
  const [error, setError] = React.useState<string>();

  // Fallback camera-capture state.
  const videoRef = React.useRef<HTMLVideoElement>(null);
  const streamRef = React.useRef<MediaStream | null>(null);
  const [failCount, setFailCount] = React.useState(0);

  // Liveness poll bookkeeping.
  const timer = React.useRef<ReturnType<typeof setInterval> | null>(null);
  const polls = React.useRef(0);

  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/mobile-otp");
  }, [mounted, appId, router]);

  const stopPoll = React.useCallback(() => {
    if (timer.current) { clearInterval(timer.current); timer.current = null; }
  }, []);
  const stopCamera = React.useCallback(() => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
  }, []);
  React.useEffect(() => () => { stopPoll(); stopCamera(); }, [stopPoll, stopCamera]);

  const advance = React.useCallback(() => {
    stopPoll();
    stopCamera();
    setPhase("done");
    setTimeout(() => router.push(nextAfterStep("/signup/agreement")), 700);
  }, [router, stopPoll, stopCamera]);

  // ---- Primary: Signzy liveness video journey ----
  const pollLiveness = React.useCallback(async () => {
    if (appId == null) return;
    try {
      polls.current += 1;
      const r = await verificationApi.selfieLivenessStatus(appId);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") {
        advance();
      } else if (polls.current >= POLL_LIMIT) {
        // Not finished in the window — never hard-block; advance to manual review.
        advance();
      }
      // Otherwise still PENDING (journey in progress) — keep polling.
    } catch (err) {
      stopPoll();
      setError(formatApiError(err, "Lost connection to the liveness check."));
    }
  }, [appId, advance, stopPoll]);

  const startLiveness = async () => {
    if (appId == null) return;
    setPhase("starting");
    setError(undefined);
    setResult(null);
    try {
      const r = await verificationApi.selfieLivenessInit(appId);
      if (r.derived?.fallback === true) {
        setPhase("capture-idle"); // Signzy liveness unavailable → camera-capture fallback.
        return;
      }
      const url = typeof r.derived?.videoUrl === "string" ? (r.derived.videoUrl as string) : null;
      if (!url) { setPhase("capture-idle"); return; }
      setVideoUrl(url);
      setPhase("liveness");
      stopPoll();
      polls.current = 0;
      timer.current = setInterval(() => { void pollLiveness(); }, POLL_MS);
    } catch (err) {
      setError(formatApiError(err, "Couldn't start the liveness check — capture a selfie instead."));
      setPhase("capture-idle");
    }
  };

  // ---- Fallback: camera capture → Digitap face-match ----
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
      setPhase("capture-live");
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

    setPhase("capture-uploading");
    setError(undefined);
    try {
      const { key, url } = await verificationApi.presignUpload(appId, { docType: "SELFIE", fileName: "selfie.jpg", contentType: "image/jpeg" });
      await verificationApi.putToPresignedUrl(url, blob, "image/jpeg");
      const r = await verificationApi.selfie(appId, key);
      setResult(r);
      if (r.status === "PASS" || r.status === "REVIEW") {
        advance();
        return;
      }
      // FAIL: allow up to 2 retries (3 total attempts), then proceed for manual review.
      if (failCount >= 2) {
        advance();
      } else {
        setFailCount((n) => n + 1);
        setPhase("capture-live");
      }
    } catch (err) {
      setError(formatApiError(err, "Could not verify your selfie — please try again."));
      setPhase("capture-live");
    }
  };

  const inCaptureMode = phase === "capture-idle" || phase === "capture-live" || phase === "capture-uploading";

  return (
    <div>
      <div className="form-card text-center">
        <h1 className="text-2xl">Verify it&apos;s really you</h1>
        <p className="mx-auto mb-6 max-w-md text-muted">
          {inCaptureMode
            ? "We couldn't start the quick video check — take a selfie instead. Look straight at the camera in good lighting."
            : "A quick, secure face check confirms you're a real person and matches you to your Aadhaar photo. It takes a few seconds."}
        </p>

        {/* Primary: Signzy liveness iframe. allow="camera" delegates camera to the provider frame. */}
        {phase === "liveness" && videoUrl ? (
          <div className="mx-auto mb-4 w-full max-w-sm overflow-hidden rounded-2xl border border-line bg-black">
            <iframe
              title="Liveness check"
              src={videoUrl}
              allow="camera; microphone; fullscreen"
              className="aspect-[3/4] w-full"
            />
          </div>
        ) : null}

        {/* Fallback: camera capture circle. */}
        {inCaptureMode ? (
          <div className="relative mx-auto mb-3 grid aspect-square w-full max-w-xs place-items-center overflow-hidden rounded-full border-4 border-dashed border-line bg-grey-100">
            <video
              ref={videoRef}
              playsInline
              muted
              className={`h-full w-full object-cover ${phase === "capture-live" || phase === "capture-uploading" ? "" : "hidden"}`}
            />
            {phase === "capture-live" || phase === "capture-uploading" ? (
              <div className="pointer-events-none absolute inset-0 grid place-items-center">
                <div className="h-[80%] w-[80%] rounded-full border-2 border-dashed border-white/90 shadow-[0_0_0_9999px_rgba(12,37,64,0.35)]" />
              </div>
            ) : null}
            {phase === "capture-idle" ? <Camera size={64} className="text-muted" /> : null}
          </div>
        ) : null}

        {/* Idle hero icon (before starting). */}
        {phase === "idle" || phase === "starting" ? (
          <div className="relative mx-auto mb-4 grid aspect-square w-full max-w-xs place-items-center overflow-hidden rounded-full border-4 border-dashed border-line bg-grey-100">
            <ScanFace size={64} className="text-muted" />
          </div>
        ) : null}

        {phase === "done" ? (
          <div className="mb-4 flex items-center justify-center gap-2 text-sm font-semibold text-success-700">
            Verified — continuing… <ArrowRight size={16} />
          </div>
        ) : null}

        {/* Actions */}
        {phase === "idle" ? (
          <button onClick={startLiveness} className="btn btn-navy">
            <ScanFace size={16} /> Start face check
          </button>
        ) : phase === "starting" ? (
          <div className="flex items-center justify-center gap-2 text-sm text-muted">
            <Loader2 size={16} className="animate-spin" /> Starting…
          </div>
        ) : phase === "liveness" ? (
          <p className="mx-auto max-w-xs text-sm text-muted">
            <Loader2 size={14} className="mr-1 inline animate-spin" />
            Follow the prompts above — this finishes automatically.
          </p>
        ) : phase === "capture-idle" ? (
          <button onClick={startCamera} className="btn btn-navy">
            <Camera size={16} /> Start camera
          </button>
        ) : phase === "capture-live" ? (
          <button onClick={capture} className="btn btn-gold">
            {failCount > 0 ? <RefreshCw size={16} /> : null}
            {failCount > 0 ? "Retake selfie" : "Capture selfie"}
          </button>
        ) : phase === "capture-uploading" ? (
          <div className="flex items-center justify-center gap-2 text-sm text-muted">
            <Loader2 size={16} className="animate-spin" /> Verifying your selfie…
          </div>
        ) : null}

        <StepResultBanner result={phase === "done" ? result : null} />
        {failCount === 1 && result?.status === "FAIL" ? (
          <p className="mt-2 text-xs text-muted">2 attempts used — 1 retake remaining before we proceed to manual review.</p>
        ) : null}
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <div className="mt-8">
        <a href="/signup/penny-drop" className="btn btn-outline btn-sm" onClick={() => { stopPoll(); stopCamera(); }}>Back</a>
      </div>
      <Reassurance />
    </div>
  );
}
