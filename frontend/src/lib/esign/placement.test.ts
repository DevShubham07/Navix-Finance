import { describe, expect, it } from "vitest";
import {
  DEFAULT_BOX_HEIGHT,
  DEFAULT_BOX_WIDTH,
  MIN_BOX_HEIGHT,
  MIN_BOX_WIDTH,
  clampBox,
  dropBox,
  moveBox,
  pagesOf,
  ptToPx,
  pxToPt,
  repeatOnAllPages,
  resizeBox,
} from "./placement";

const A4 = { width: 595, height: 842 };
const box = (over = {}) => ({ page: 1, x: 50, y: 100, width: 160, height: 70, ...over });

describe("placement", () => {
  it("converts px and pt through the render scale", () => {
    expect(pxToPt(300, 0.5)).toBe(600);
    expect(ptToPx(200, 1.5)).toBe(300);
  });

  it("drops a default box centred on the click and clamps it inside the page", () => {
    const mid = dropBox(2, 300, 400, A4);
    expect(mid).toEqual({ page: 2, x: 300 - DEFAULT_BOX_WIDTH / 2, y: 400 - DEFAULT_BOX_HEIGHT / 2, width: 160, height: 70 });
    const corner = dropBox(1, 590, 840, A4);
    expect(corner.x + corner.width).toBe(A4.width);
    expect(corner.y + corner.height).toBe(A4.height);
    const origin = dropBox(1, 0, 0, A4);
    expect(origin.x).toBe(0);
    expect(origin.y).toBe(0);
  });

  it("moves but never leaves the page", () => {
    expect(moveBox(box(), 10, -20, A4)).toMatchObject({ x: 60, y: 80 });
    expect(moveBox(box(), -999, -999, A4)).toMatchObject({ x: 0, y: 0 });
    const far = moveBox(box(), 999, 999, A4);
    expect(far.x + far.width).toBe(A4.width);
    expect(far.y + far.height).toBe(A4.height);
  });

  it("resizes from the corner within the minimum and the page edge", () => {
    expect(resizeBox(box(), 40, 30, A4)).toMatchObject({ width: 200, height: 100 });
    expect(resizeBox(box(), -999, -999, A4)).toMatchObject({ width: MIN_BOX_WIDTH, height: MIN_BOX_HEIGHT });
    const big = resizeBox(box(), 9999, 9999, A4);
    expect(big.x + big.width).toBe(A4.width);
    expect(big.y + big.height).toBe(A4.height);
  });

  it("clamps an oversize box to the page", () => {
    expect(clampBox(box({ width: 900 }), A4)).toMatchObject({ x: 0, width: A4.width });
  });

  it("repeats a box only onto pages that lack one for the signer", () => {
    const pages = [A4, A4, { width: 400, height: 300 }];
    const src = box({ x: 380, y: 250 });
    const added = repeatOnAllPages([src, box({ page: 2 })], src, pages);
    expect(added).toHaveLength(1);
    expect(added[0].page).toBe(3);
    // clamped into the smaller page
    expect(added[0].x + added[0].width).toBeLessThanOrEqual(400);
    expect(added[0].y + added[0].height).toBeLessThanOrEqual(300);
  });

  it("lists distinct sorted pages", () => {
    expect(pagesOf([box({ page: 3 }), box({ page: 1 }), box({ page: 3 })])).toEqual([1, 3]);
  });
});
