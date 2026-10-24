# Security and privacy notes

- Only three database roles exist: `OWNER`, `MANAGER`, and `STUDENT`.
- Spring Security authenticates BCrypt password hashes in a server session. Session fixation is mitigated, cookies are HttpOnly and SameSite, unsafe requests require CSRF, CORS is restricted to the configured frontend origin, and login attempts are throttled per source address in process memory.
- Password reset tokens are random, hashed before storage, expire after 30 minutes, are single use, and return the same public response for unknown accounts.
- Student ID scoping is checked server-side for profiles, invoices, payments, notifications, and file retrieval.
- File names on disk are generated UUIDs. Files are stored outside public assets, content signatures and size are checked, and retrieval is authorized. Only the current official logo is served publicly.
- Payment approval and invoice balances run in a database transaction. Payment approval is retry-safe, receipt numbers are unique, rejected payments require a reason, and reversal preserves history.
- Audit rows are inserted by services and there is no API for users to edit or delete them.
- Keep production credentials outside source control, serve HTTPS, set `APP_SESSION_SECURE=true`, use strong SMTP/database passwords, and put the API behind a trusted reverse proxy.

The current throttle is process-local and resets when the API restarts. Production deployments with multiple API replicas should use shared rate-limit/session infrastructure and an external session store before scaling horizontally.
