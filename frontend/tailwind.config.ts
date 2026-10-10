import type { Config } from "tailwindcss";

/**
 * DhanBoost — Tailwind tokens.
 *
 * Every colour here is a pointer into `src/styles/theme.css` (the single source of truth); the
 * values live there as RGB channels so `/opacity` modifiers (`text-navy/60`) keep working.
 * Re-skin by changing theme.css VALUES — never rename a token here (the names are load-bearing
 * across ~54 screens: `navy` = primary dark ink, `gold` = brand accent, `ivory` = page canvas).
 */
const v = (name: string) => `rgb(var(--c-${name}) / <alpha-value>)`;

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  // Disable Tailwind's `container` so the design-system `.container`
  // (max-width 1200px) defined in globals.css is unambiguous.
  corePlugins: { container: false },
  theme: {
    extend: {
      colors: {
        navix: {
          DEFAULT: v("navy-800"),
          50: v("navy-50"),
          100: v("navy-100"),
          200: v("navy-200"),
          300: v("navy-300"),
          400: v("navy-400"),
          500: v("navy-500"),
          600: v("navy-600"),
          700: v("navy-700"),
          800: v("navy-800"),
          900: v("navy-900"),
        },
        navy: {
          DEFAULT: v("navy-800"),
          900: v("navy-900"),
          800: v("navy-800"),
          700: v("navy-700"),
          tint: v("navy-tint"),
          deep: v("navy-900"),
        },
        gold: {
          DEFAULT: v("gold-500"),
          dark: v("gold-dark"),
          soft: v("gold-soft"),
          50: v("gold-50"),
          300: v("gold-300"),
          400: v("gold-400"),
          500: v("gold-500"),
          600: v("gold-600"),
          700: v("gold-700"),
        },
        ivory: v("ivory"),
        canvas: v("canvas"),
        frame: v("frame"),
        surface: v("surface"),
        paper: v("paper"),
        charcoal: v("charcoal"),
        ink: v("ink"),
        slate: v("slate"),
        muted: v("muted"),
        line: v("line"),
        grey: {
          50: v("grey-50"),
          100: v("grey-100"),
          200: v("grey-200"),
        },
        success: {
          DEFAULT: v("success-600"),
          50: v("success-50"),
          100: v("success-100"),
          500: v("success-500"),
          600: v("success-600"),
          700: v("success-700"),
          800: v("success-800"),
          900: v("success-900"),
          bg: v("success-50"),
        },
        warning: {
          DEFAULT: v("warning-600"),
          50: v("warning-50"),
          100: v("warning-100"),
          500: v("warning-500"),
          600: v("warning-600"),
          700: v("warning-700"),
          800: v("warning-800"),
          900: v("warning-900"),
          bg: v("warning-50"),
        },
        error: {
          DEFAULT: v("error-600"),
          50: v("error-50"),
          100: v("error-100"),
          500: v("error-500"),
          600: v("error-600"),
          700: v("error-700"),
          800: v("error-800"),
          900: v("error-900"),
        },
        info: {
          DEFAULT: v("info-600"),
          50: v("info-50"),
          100: v("info-100"),
          500: v("info-500"),
          600: v("info-600"),
          700: v("info-700"),
        },
        chart: {
          ember: v("chart-ember"),
          sun: v("chart-sun"),
          mint: v("chart-mint"),
          violet: v("chart-violet"),
          ink: v("chart-ink"),
          sky: v("chart-sky"),
          track: v("chart-track"),
        },
        neutral: {
          50: v("grey-50"),
          100: v("navy-50"),
          200: v("navy-100"),
          300: v("navy-200"),
          400: v("navy-300"),
          500: v("navy-400"),
          600: v("navy-500"),
          700: v("navy-600"),
          800: v("navy-700"),
          900: v("navy-800"),
        },
      },
      fontFamily: {
        // `serif` is the HEADING face and `mono` the FIGURE face — both Inter (names kept so existing
        // usages cascade). `display` is the condensed caps face for page titles + headline figures.
        sans: ["var(--font-body)"],
        serif: ["var(--serif)"],
        mono: ["var(--mono)"],
        display: ["var(--font-display)"],
      },
      // Values are 80% of the previous scale (20% smaller base typography); line-heights
      // scaled proportionally so leading stays visually consistent at the new size.
      fontSize: {
        xs: ["9.6px", { lineHeight: "12.8px" }],
        sm: ["11.2px", { lineHeight: "16px" }],
        base: ["12.8px", { lineHeight: "20.8px" }],
        lg: ["14.4px", { lineHeight: "22.4px" }],
        xl: ["16px", { lineHeight: "22.4px" }],
        "2xl": ["19.2px", { lineHeight: "25.6px" }],
        "3xl": ["24px", { lineHeight: "30.4px" }],
        "4xl": ["28.8px", { lineHeight: "33.6px" }],
        "5xl": ["38.4px", { lineHeight: "1.1" }],
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
      // Generous, soft radii (reference: pill controls, 22–28px cards). Mirrors theme.css --r-*.
      borderRadius: {
        none: "0",
        sm: "10px",
        DEFAULT: "14px",
        base: "14px",
        md: "14px",
        lg: "18px",
        xl: "22px",
        "2xl": "28px",
        "3xl": "32px",
        full: "9999px",
      },
      // Soft shadows, tuned so small dropdowns/tooltips (shadow-md/lg) stay crisp
      // while large surfaces keep the design's diffuse cast.
      boxShadow: {
        xs: "var(--shadow-xs)",
        sm: "var(--shadow-sm)",
        base: "var(--shadow)",
        md: "var(--shadow-md)",
        lg: "var(--shadow-lg)",
        xl: "var(--shadow-lg)",
        pill: "var(--shadow-pill)",
        gold: "var(--shadow-gold)",
        focus: "var(--ring-focus)",
      },
      transitionTimingFunction: {
        "out-expo": "cubic-bezier(.16, 1, .3, 1)",
      },
      animation: {
        spin: "spin 1s linear infinite",
        // Kit motion (see globals.css "Motion"): panels rise in, bars grow, chips pop.
        rise: "rise .6s cubic-bezier(.16, 1, .3, 1) backwards",
        "grow-y": "growY .9s cubic-bezier(.16, 1, .3, 1) backwards",
        pop: "pop .45s cubic-bezier(.34, 1.56, .64, 1) backwards",
        "fade-up": "fadeUp 0.4s ease",
        // Two copies of the message sit side by side, so shifting by exactly half loops seamlessly.
        ticker: "ticker 38s linear infinite",
      },
      keyframes: {
        rise: {
          "0%": { opacity: "0", transform: "translateY(10px) scale(.985)" },
          "100%": { opacity: "1", transform: "none" },
        },
        growY: {
          "0%": { transform: "scaleY(0)" },
          "100%": { transform: "scaleY(1)" },
        },
        pop: {
          "0%": { opacity: "0", transform: "scale(.4)" },
          "100%": { opacity: "1", transform: "scale(1)" },
        },
        fadeUp: {
          "0%": { opacity: "0", transform: "translateY(14px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        ticker: {
          "0%": { transform: "translateX(0)" },
          "100%": { transform: "translateX(-50%)" },
        },
      },
    },
  },
  plugins: [],
};

export default config;
