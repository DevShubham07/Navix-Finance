/**
 * Pure geometry for the eSign signature placer. Everything is in PDF points with the origin at the
 * TOP-LEFT of the page as displayed (rotation applied) - the same frame the backend validates and
 * Signzy is fed from. CSS pixels only exist at the edge, via the render scale (px per point).
 */

export interface Box {
  /** 1-based page number. */
  page: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface PageSize {
  width: number;
  height: number;
}

/** Signzy's documented minimum for its document signer; mirrored by the backend. */
export const MIN_BOX_WIDTH = 130;
export const MIN_BOX_HEIGHT = 70;
export const DEFAULT_BOX_WIDTH = 160;
export const DEFAULT_BOX_HEIGHT = 70;
export const MAX_BOXES_PER_SIGNER = 50;

export const pxToPt = (px: number, scale: number): number => px / scale;
export const ptToPx = (pt: number, scale: number): number => pt * scale;

const clamp = (v: number, lo: number, hi: number): number => Math.min(Math.max(v, lo), Math.max(lo, hi));

/** Keep a box fully inside the page: shrink it if it is bigger than the page, then slide it in. */
export function clampBox(b: Box, page: PageSize): Box {
  const width = Math.min(b.width, page.width);
  const height = Math.min(b.height, page.height);
  return {
    ...b,
    width,
    height,
    x: clamp(b.x, 0, page.width - width),
    y: clamp(b.y, 0, page.height - height),
  };
}

/** A default box centred on a click point (in points), clamped inside the page. */
export function dropBox(pageNumber: number, xPt: number, yPt: number, page: PageSize): Box {
  return clampBox(
    {
      page: pageNumber,
      x: xPt - DEFAULT_BOX_WIDTH / 2,
      y: yPt - DEFAULT_BOX_HEIGHT / 2,
      width: DEFAULT_BOX_WIDTH,
      height: DEFAULT_BOX_HEIGHT,
    },
    page,
  );
}

export function moveBox(b: Box, dxPt: number, dyPt: number, page: PageSize): Box {
  return clampBox({ ...b, x: b.x + dxPt, y: b.y + dyPt }, page);
}

/** Resize from the bottom-right corner: the minimum size and the page edge bound it. */
export function resizeBox(b: Box, dxPt: number, dyPt: number, page: PageSize): Box {
  return {
    ...b,
    width: clamp(b.width + dxPt, MIN_BOX_WIDTH, page.width - b.x),
    height: clamp(b.height + dyPt, MIN_BOX_HEIGHT, page.height - b.y),
  };
}

/**
 * Copy `source` (x/y/size) onto every page that has no box yet for this signer. `pages[i]` is page i+1.
 * Returns only the boxes to add.
 */
export function repeatOnAllPages(existing: Box[], source: Box, pages: PageSize[]): Box[] {
  const have = new Set(existing.map((b) => b.page));
  const added: Box[] = [];
  pages.forEach((size, i) => {
    const page = i + 1;
    if (!have.has(page)) added.push(clampBox({ ...source, page }, size));
  });
  return added;
}

/** Distinct sorted pages, "1, 3" - for the detail view. */
export function pagesOf(boxes: Box[]): number[] {
  return [...new Set(boxes.map((b) => b.page))].sort((a, b) => a - b);
}
