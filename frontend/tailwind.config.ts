import type { Config } from "tailwindcss";

/**
 * NAVIX Finance — unified design tokens (2026 "calendar" design system).
 * Palette: navy #0C2540 · emerald accent #14A06B (token name kept as `gold`/`--gold-*`) · cream #FDFBF6 · ink #0C2238 · slate #46566E.
 * Type: Inter (display, body, and figures).
 *
 * Token NAMES are unchanged from the previous "Classic Corporate" theme so the
 * 54 functional screens cascade automatically; only the VALUES move to the new
 * system (which the marketing site already uses, scoped under .navix-mkt). The
 * `navix`/`navy` ramps are a deep-navy ramp; brand tokens (navy/gold/ivory/ink/
 * muted/line) mirror the CSS variables in globals.css.
 */
const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  // Disable Tailwind's `container` so the design-system `.container`
  // (max-width 1200px) defined in globals.css is unambiguous.
  corePlugins: { container: false },
  theme: {
    extend: {
      colors: {
        // Primary brand ramp (deep navy)
        navix: {
          DEFAULT: "#0C2540",
          50: "#EAF0F6",
          100: "#D5E0EC",
          200: "#AEC3D9",
          300: "#7E9CC0",
          400: "#4E739B",
          500: "#2C6298",
          600: "#1B4A7A",
          700: "#12365C",
          800: "#0C2540",
          900: "#081A31",
        },
        navy: {
          DEFAULT: "#0C2540",
          900: "#081A31",
          800: "#0C2540",
          700: "#12365C",
          tint: "#EAEFF6",
          deep: "#081A31",
        },
        // Brand accent — emerald green. Token name kept as `gold` (load-bearing
        // across ~98 files) per the re-skin rule: remap VALUES, never rename.
        gold: {
          DEFAULT: "#14A06B",
          dark: "#0B6B46",
          soft: "#A7E8CE",
          50: "#E7F6EF",
        },
        ivory: "#FDFBF6",
        charcoal: "#2D3A4A",
        ink: "#0C2238",
        muted: "#8593A6",
        line: "#EAE1D0",
        // Warm light neutrals (mirror the design --cream-* / --line-* vars).
        grey: {
          50: "#FBF7F0",
          100: "#F7F2E9",
          200: "#EFE7D6",
        },
        // Semantic status colours (success aligned to design --success #1C9B6A)
        success: {
          DEFAULT: "#1C9B6A",
          50: "#E7F5EF",
          100: "#C5E9D9",
          500: "#22A06B",
          600: "#1C9B6A",
          700: "#167A54",
          800: "#115C40",
          900: "#0C3F2C",
          bg: "#E7F5EF",
        },
        warning: {
          DEFAULT: "#8A5A06",
          50: "#FBF1D8",
          100: "#F6E3B0",
          500: "#B5820E",
          600: "#8A5A06",
          700: "#6E4B08",
          800: "#5A3D05",
          900: "#412c04",
          bg: "#FBF1D8",
        },
        error: {
          DEFAULT: "#B3261E",
          50: "#FCEBEA",
          100: "#F9D4D1",
          500: "#D33C32",
          600: "#B3261E",
          700: "#8F1E18",
          800: "#6f1712",
          900: "#54110d",
        },
        info: {
          DEFAULT: "#2C6298",
          50: "#EAF0F6",
          100: "#D5E0EC",
          500: "#2C6298",
          600: "#1B4A7A",
          700: "#12365C",
        },
        neutral: {
          50: "#F6F5F2",
          100: "#ECEAE4",
          200: "#DEDBD2",
          300: "#C6C3BA",
          400: "#9C9A93",
          500: "#6B7280",
          600: "#4B5563",
          700: "#374151",
          800: "#1F2937",
          900: "#0C2540",
        },
      },
      fontFamily: {
        // Inter powers everything (2026 system). `sans`/`serif`/`mono` all
        // resolve to Inter — the `serif` key is kept only so the existing
        // `font-serif` heading usages cascade (it is NOT a literal serif), and
        // `mono` is kept so `font-mono` figure usages cascade (Inter's tabular
        // figures keep them aligned via `font-feature-settings: "tnum"`).
        sans: ["var(--font-inter)", "Inter", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Roboto", "sans-serif"],
        serif: ["var(--font-inter)", "Inter", "system-ui", "sans-serif"],
        mono: ["var(--font-inter)", "Inter", "system-ui", "sans-serif"],
      },
      fontSize: {
        xs: ["12px", { lineHeight: "16px" }],
        sm: ["14px", { lineHeight: "20px" }],
        base: ["16px", { lineHeight: "26px" }],
        lg: ["18px", { lineHeight: "28px" }],
        xl: ["20px", { lineHeight: "28px" }],
        "2xl": ["24px", { lineHeight: "32px" }],
        "3xl": ["30px", { lineHeight: "38px" }],
        "4xl": ["36px", { lineHeight: "42px" }],
        "5xl": ["48px", { lineHeight: "1.1" }],
      },
      spacing: {
        0: "0",
        1: "4px",
        2: "8px",
        3: "12px",
        4: "16px",
        5: "20px",
        6: "24px",
        7: "28px",
        8: "32px",
        9: "36px",
        10: "40px",
        11: "44px",
        12: "48px",
        14: "56px",
        16: "64px",
        20: "80px",
        24: "96px",
        28: "112px",
        32: "128px",
      },
      maxWidth: {
        container: "1200px",
        content: "720px",
      },
      // Softer, larger radii to match the new design system (--r-* vars).
      borderRadius: {
        none: "0",
        sm: "8px",
        DEFAULT: "12px",
        base: "12px",
        md: "12px",
        lg: "16px",
        xl: "20px",
        "2xl": "26px",
        full: "9999px",
      },
      // Soft shadows, tuned so small dropdowns/tooltips (shadow-md/lg) stay crisp
      // while large surfaces keep the design's diffuse cast.
      boxShadow: {
        xs: "0 1px 2px rgba(12, 37, 64, .05)",
        sm: "0 6px 18px -10px rgba(12, 37, 64, .18)",
        base: "0 10px 26px -14px rgba(12, 37, 64, .20)",
        md: "0 16px 36px -18px rgba(12, 37, 64, .22)",
        lg: "0 24px 48px -20px rgba(12, 37, 64, .28)",
        xl: "0 40px 80px -36px rgba(12, 37, 64, .40)",
        gold: "0 16px 34px -16px rgba(20, 160, 107, .42)",
        focus: "0 0 0 4px rgba(63, 191, 137, .22)",
      },
      animation: {
        spin: "spin 1s linear infinite",
        "fade-up": "fadeUp 0.4s ease",
      },
      keyframes: {
        fadeUp: {
          "0%": { opacity: "0", transform: "translateY(14px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
      },
    },
  },
  plugins: [],
};

export default config;
