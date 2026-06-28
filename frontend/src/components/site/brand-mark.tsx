import Image from "next/image";

/**
 * NAVIX emblem — the official brand mark (the gold/silver compass-shield, same
 * image as the favicon `app/icon.png` and the borrower/staff app `Brand`). Kept
 * consistent across marketing + app; do not swap back to a plain star.
 */
export function BrandMark() {
  return (
    <span className="logo logo--img">
      <Image src="/navix-mark.png" alt="NAVIX" width={36} height={36} priority />
    </span>
  );
}
