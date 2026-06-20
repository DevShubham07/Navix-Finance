// Borrower route-group shell: simple header + main content area.
// TODO: add auth guard (redirect unauthenticated users to /login) once
//       session handling is wired up against the backend IAM module.

export default function BorrowerLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div>
      <header>
        <span>NAVIX Finance</span>
        {/* TODO: borrower nav + profile menu */}
      </header>
      <main>{children}</main>
    </div>
  );
}
