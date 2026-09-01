/**
 * Client-side image compression for direct browser→S3 uploads.
 *
 * Borrowers upload multi-MB phone screenshots/photos over slow mobile data — the raw file
 * regularly killed the presigned PUT mid-upload ("Failed to fetch"). Downscaling to ≤1920px
 * JPEG q0.8 turns a 2–8 MB capture into ~200–400 KB with no legibility loss, and takes well
 * under a second on a phone.
 *
 * Non-images (PDFs), GIFs, already-small files, and anything the browser can't decode pass
 * through untouched — callers can hand every picked file to this unconditionally.
 */

const MAX_DIMENSION = 1920;
const JPEG_QUALITY = 0.8;
const SKIP_BELOW_BYTES = 300 * 1024;

export async function compressImage(file: File): Promise<File> {
  if (!file.type.startsWith("image/") || file.type === "image/gif" || file.size < SKIP_BELOW_BYTES) {
    return file;
  }
  try {
    // createImageBitmap applies EXIF orientation by default, so rotated phone photos stay upright.
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, MAX_DIMENSION / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const ctx = canvas.getContext("2d");
    if (!ctx) return file;
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, "image/jpeg", JPEG_QUALITY),
    );
    // Only swap in the compressed version when it actually saved bytes.
    if (!blob || blob.size >= file.size) return file;
    return new File([blob], file.name.replace(/\.[^.]*$/, "") + ".jpg", { type: "image/jpeg" });
  } catch {
    return file;
  }
}
