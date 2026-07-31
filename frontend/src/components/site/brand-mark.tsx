import Image from "next/image";

/**
 * DhanBoost emblem — the official brand mark (the green rupee/growth-arrow tile, same
 * image as the favicon `app/icon.png` and the borrower/staff app `Brand`). The art
 * carries its own tile, so `.logo--img` drops the navy plate behind it.
 */
export function BrandMark() {
  return (
    <span className="logo logo--img">
      <Image src="/navix-mark.png" alt="DhanBoost" width={36} height={36} priority />
    </span>
  );
}
