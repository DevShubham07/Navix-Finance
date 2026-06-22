import type { Config } from "tailwindcss";

/**
 * NAVIX Finance — "Classic Corporate" design tokens.
 * Palette: navy #1B3A6B · gold #C9A227 · ivory #FAFAF7 · charcoal #2B2B2B.
 * Type: Source Serif 4 (headings) · Inter (body). Radius 4px.
 *
 * The `navix` scale is intentionally a navy ramp so existing primitives that
 * reference `navix-600` etc. render on-brand. Exact brand tokens are also
 * exposed directly (navy/gold/ivory/ink/muted/line) and mirror the CSS
 * variables defined in globals.css.
 */
const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  // Disable Tailwind's `container` so the design-system `.container`
  // (max-width 1200px) defined in globals.css is unambiguous.
  corePlugins: { container: false },
  theme: {
    extend: {
      colors: {
        // Primary brand ramp (navy)
        navix: {
          DEFAULT: "#1B3A6B",
          50: "#EEF2F8",
          100: "#DCE6F4",
          200: "#BFD0E6",
          300: "#9FB4CD",
          400: "#5E789F",
          500: "#29528F",
          600: "#1B3A6B",
          700: "#16315C",
          800: "#142B50",
          900: "#0F1F3A",
        },
        navy: {
          DEFAULT: "#1B3A6B",
          900: "#142B50",
          800: "#1B3A6B",
          700: "#29528F",
          tint: "#EEF2F8",
          deep: "#0F1F3A",
        },
        gold: {
          DEFAULT: "#C9A227",
          dark: "#A9851A",
          soft: "#F1E6BF",
          50: "#FBF3DA",
        },
        ivory: "#FAFAF7",
        charcoal: "#2B2B2B",
        ink: "#1F2D38",
        muted: "#5B6B7B",
        line: "#DDE2E8",
        // Mirror the design's --grey-100 / --grey-200 CSS vars as utilities.
        grey: {
          100: "#F4F5F7",
          200: "#E8EAED",
        },
        // Semantic status colours (match design --ok / --warn / --danger)
        success: {
          DEFAULT: "#1E7C54",
          50: "#E7F3EC",
          100: "#CDE7D8",
          500: "#22A06B",
          600: "#1E7C54",
          700: "#186544",
          800: "#134f35",
          900: "#0f3f2a",
          bg: "#E7F3EC",
        },
        warning: {
          DEFAULT: "#8A5A06",
          50: "#FBF3DA",
          100: "#F6E7B8",
          500: "#B5820E",
          600: "#8A5A06",
          700: "#6E4B08",
          800: "#5A3D05",
          900: "#412c04",
          bg: "#FBF3DA",
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
          DEFAULT: "#29528F",
          50: "#EEF2F8",
          100: "#DCE6F4",
          500: "#29528F",
          600: "#1B3A6B",
          700: "#16315C",
        },
        neutral: {
          50: "#F4F5F7",
          100: "#E8EAED",
          200: "#DDE2E8",
          300: "#C5CCD4",
          400: "#9AA7B4",
          500: "#5B6B7B",
          600: "#475563",
          700: "#374151",
          800: "#1F2D38",
          900: "#142B50",
        },
      },
      fontFamily: {
        sans: ["var(--font-sans)", "Inter", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Roboto", "sans-serif"],
        serif: ["var(--font-serif)", "Source Serif 4", "Georgia", "Times New Roman", "serif"],
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
      borderRadius: {
        none: "0",
        sm: "3px",
        DEFAULT: "4px",
        base: "4px",
        md: "4px",
        lg: "6px",
        xl: "10px",
        "2xl": "14px",
        full: "9999px",
      },
      boxShadow: {
        xs: "0 1px 2px rgba(20, 43, 80, .06)",
        sm: "0 1px 2px rgba(20, 43, 80, .06)",
        base: "0 2px 10px rgba(20, 43, 80, .07)",
        md: "0 2px 10px rgba(20, 43, 80, .07)",
        lg: "0 8px 28px rgba(20, 43, 80, .10)",
        xl: "0 18px 44px rgba(20, 43, 80, .14)",
        focus: "0 0 0 3px rgba(27, 58, 107, .12)",
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
