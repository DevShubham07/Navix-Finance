/** NAVIX star/checkmark emblem (ported from the design export). */
export function BrandMark() {
  return (
    <span className="logo">
      <svg viewBox="0 0 32 32" fill="none">
        <path
          d="M16 2l2.9 8.5L27.5 11l-6.8 5.5 2.6 8.4L16 20.2 8.7 24.9l2.6-8.4L4.5 11l8.6-.5L16 2z"
          fill="#F4C95B"
        />
        <circle cx="16" cy="15.5" r="4.4" fill="#0C2540" stroke="#F4C95B" strokeWidth="1.2" />
        <path
          d="M14 15.5l1.6 1.6 3-3.2"
          stroke="#F4C95B"
          strokeWidth="1.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </span>
  );
}
