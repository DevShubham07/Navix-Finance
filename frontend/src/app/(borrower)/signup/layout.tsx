// Step-progress shell wrapping the 12-step sign-up flow.
// TODO: derive the active step from the current pathname and render a
//       progress indicator (PAN -> mobile+OTP -> employment -> salary ->
//       email -> bank -> financials -> address-proof -> review).

export default function SignupLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div>
      <nav aria-label="Sign-up progress">
        {/* TODO: step-progress indicator (current step / total steps) */}
        <p>Sign-up</p>
      </nav>
      <div>{children}</div>
    </div>
  );
}
