"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { UploadCloud, FileCheck2 } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { usePersistedField } from "@/hooks/use-persisted-field";
import { useBorrowerJourney } from "@/lib/mock/borrower";
import { fileToBase64 } from "@/lib/api/applications";

const DOC_TYPES = ["Aadhaar", "Passport", "Voter ID", "Driving licence", "Utility bill"].map((d) => ({ value: d, label: d }));

export default function SignupAddressProofPage() {
  const router = useRouter();
  const { applicant, updateApplicant, setKyc, setAddressProofDoc, addressProofDoc } = useBorrowerJourney();
  const [docType, setDocType] = React.useState("Aadhaar");
  const [addressLine, setAddressLine] = usePersistedField(applicant.addressLine);
  const [city, setCity] = usePersistedField(applicant.city);
  const [pin, setPin] = usePersistedField(applicant.pin);
  const [fileName, setFileName] = React.useState<string | null>(addressProofDoc?.fileName ?? null);
  const [uploadErr, setUploadErr] = React.useState<string | null>(null);
  const [touched, setTouched] = React.useState(false);

  // Read the chosen file into base64 and stash it on the journey, so review-submit uploads it to the
  // backend (POST …/documents). Previously this step was cosmetic and no document ever reached staff.
  const onFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploadErr(null);
    if (file.size > 10 * 1024 * 1024) { setUploadErr("File must be under 10 MB."); return; }
    try {
      const dataBase64 = await fileToBase64(file);
      setAddressProofDoc({ docType, fileName: file.name, contentType: file.type || undefined, dataBase64 });
      setFileName(file.name);
    } catch {
      setUploadErr("Could not read that file — please try another.");
    }
  };

  const ok = addressLine.trim().length > 4 && city.trim().length > 1 && pin.replace(/\D/g, "").length === 6 && fileName != null;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!ok) { setTouched(true); return; }
    updateApplicant({ addressLine: addressLine.trim(), city: city.trim(), pin });
    setKyc({ address: "VERIFIED" });
    router.push("/signup/review");
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">Confirm your current address and upload a matching proof.</p>
        <Select label="Document type" value={docType} onChange={(e) => setDocType(e.target.value)} options={DOC_TYPES} />
        <Input
          label="Address line"
          required
          value={addressLine}
          onChange={(e) => setAddressLine(e.target.value)}
          placeholder="Flat / house, street, area"
          autoComplete="street-address"
          error={touched && addressLine.trim().length <= 4 ? "Enter your full address" : undefined}
        />
        <div className="field-row">
          <Input
            label="City"
            required
            value={city}
            onChange={(e) => setCity(e.target.value)}
            placeholder="Bengaluru"
            error={touched && city.trim().length <= 1 ? "Enter your city" : undefined}
          />
          <Input
            label="PIN code"
            required
            inputMode="numeric"
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))}
            placeholder="560038"
            error={touched && pin.replace(/\D/g, "").length !== 6 ? "6-digit PIN" : undefined}
          />
        </div>

        <label
          className={`mt-1 flex w-full cursor-pointer flex-col items-center gap-2 rounded border-2 border-dashed p-7 text-center transition ${
            fileName ? "border-success-600 bg-success-50/50" : "border-line bg-grey-100 hover:border-navy"
          }`}
        >
          <input type="file" accept="image/*,application/pdf" className="sr-only" onChange={onFile} />
          {fileName ? (
            <>
              <FileCheck2 size={28} className="text-success-600" />
              <span className="text-sm font-semibold text-success-700">{docType} uploaded</span>
              <span className="text-xs text-muted">{fileName} · tap to replace</span>
            </>
          ) : (
            <>
              <UploadCloud size={28} className="text-navy" />
              <span className="text-sm font-semibold text-navy">Upload {docType}</span>
              <span className="text-xs text-muted">PDF or image up to 10 MB</span>
            </>
          )}
        </label>
        {uploadErr ? <p className="mt-2 text-sm text-error-600">{uploadErr}</p> : null}
        {touched && !fileName ? <p className="mt-2 text-sm text-error-600">Upload your address proof to continue</p> : null}
      </div>
      <WizardActions backHref="/signup/co-applicant" submit continueLabel="Review application" />
      <Reassurance />
    </form>
  );
}
