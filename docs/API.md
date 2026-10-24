# REST API map

All routes are under `/api`. The browser uses an HttpOnly session cookie plus a CSRF cookie/header. `GET /api/auth/csrf` obtains a CSRF token; call it before login or other state-changing requests. Protected resources return 401 without a session and 403 when the role or record scope is insufficient.

## Authentication and branding

| Method and route | Access | Purpose |
|---|---|---|
| `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` | Public / signed in | Session lifecycle |
| `POST /auth/password-reset/request`, `POST /auth/password-reset/confirm` | Public with CSRF | Single-use email reset token, 30 minute expiry |
| `GET /branding`, `GET /branding/logo` | Public | Public hostel name and logo only |
| `GET /settings`, `PUT /branding`, `POST /branding/logo` | Owner | Persistent branding and Owner report email |
| `GET /files/{id}`, `POST /files` | Signed in, file scope enforced | Private upload and retrieval |

## Hostel operations

| Method and route | Access | Purpose |
|---|---|---|
| `GET /dashboard` | All roles, role-scoped | Live role-specific metrics |
| `GET /rooms`, `POST /rooms`, `PUT /rooms/{id}`, `DELETE /rooms/{id}` | Owner, Manager | Room capacity, update, safe deactivation |
| `GET /students`, `GET /students/{id}` | Staff / own Student record | Search and retrieve student records |
| `POST /students`, `PUT /students/{id}`, `POST /students/{id}/assign`, `/photo`, `/checkout` | Owner, Manager | Registration, changes, history, photo, check-out |
| `GET /invoices`, `POST /invoices/generate?month=YYYY-MM` | Staff / own Student invoices | Fee obligations and idempotent monthly generation |
| `GET /payments`, `POST /payments` | Staff / own Student payments | Submission and review queue |
| `POST /payments/{id}/approve`, `/reject` | Owner, Manager | Verify or reject with reason; transactional receipt creation |
| `POST /payments/{id}/reverse` | Owner | Audited payment reversal; original receipt history retained |
| `GET /receipts/{paymentId}.pdf` | Owner, Manager, own Student | Official PDF receipt |
| `GET /expenses`, `POST /expenses`, `POST /expenses/{id}/reverse` | Owner, Manager | Operating costs and traceable correction |
| `GET /expense-categories` | Owner, Manager | Available user-entered expense categories |
| `GET /bills`, `POST /bills/schedules`, `POST /bills/{id}/payment` | Owner, Manager | Recurring schedules and bill payment records |
| `GET /employees`, `POST /employees`, `DELETE /employees/{id}` | Owner, Manager | Employee lifecycle |
| `GET /salaries`, `POST /salaries` | Owner, Manager | Monthly salary obligations and partial payments |
| `GET /advances`, `POST /advances`, `POST /advances/{id}/repayments` | Owner, Manager | Advances and repayment history |
| `GET /withdrawals`, `POST /withdrawals` | Owner | Separate owner withdrawals |
| `GET /notifications`, `POST /notifications/{id}/read` | Own account | In-app notifications |

## Owner controls and reports

| Method and route | Access | Purpose |
|---|---|---|
| `GET /users`, `POST /users`, `PUT /users/{id}/status` | Owner | Manager accounts and access status |
| `GET /audit-logs` | Owner | Append-only application audit events |
| `GET /ledger` | Owner, Manager | Authorized financial ledger view |
| `POST /reports?month=YYYY-MM`, `GET /reports`, `GET /reports/{id}/download` | Owner | Manual workbook generation, delivery history, secure download |
| `GET /actuator/health` | Public liveness only | Basic health status |

List routes accept `page` and `size`; the backend caps `size` at 100. Search/date filters are available on students, payments, expenses, and ledger where shown by the UI. DTOs are validated server-side; financial state is calculated and approved by services, never accepted from client totals or status values.
