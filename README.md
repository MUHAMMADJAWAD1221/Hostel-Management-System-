# INDUS HOSTEL SERVICE BADIN

A full-stack hostel administration system with three role panels: Owner, Manager, and Student. The frontend is React, TypeScript, Vite, and Tailwind CSS. The backend is Java 21 with Spring Boot, Spring Security, Spring Data JPA/Hibernate for account lookup, JDBC for transactional workflows and reporting, Flyway, and MySQL.

## Project layout

- `frontend/` — responsive web application
- `backend/` — REST API, scheduled jobs, migrations, PDF receipts, and Excel reports
- `docs/` — operations, backup, and accounting notes

## Requirements

- Java 21
- Maven 3.9+
- Node.js 20+
- MySQL 8.0+

## Local setup

1. Create a MySQL database and user, then copy `backend/.env.example` values into your shell or IDE run configuration. Never commit real credentials.
2. Start the API from `backend/` with `mvn spring-boot:run`.
3. Start the web app from `frontend/` with `npm install` and `npm run dev`.
4. Open `http://localhost:5173`. The API defaults to `http://localhost:8080`.

Flyway applies database migrations at startup. Set `SPRING_PROFILES_ACTIVE=dev` and `APP_SEED_DEMO=true` for fictional demo accounts. The local-only demo credentials are listed in `docs/SETUP.md`; never enable demo seeding in production.

## Docker Compose

Copy `.env.compose.example` to `.env`, set separate strong MySQL passwords, then run `docker compose up --build -d`. The application opens at `http://localhost:8081`. See `docs/SETUP.md` for production settings and persistence guidance.

## Configuration

See `backend/.env.example`. Important settings include MySQL access, private file storage, the hostel time zone, frontend origin, secure session cookies, Owner report email, and SMTP. Production must use HTTPS, configure trusted origins and SMTP, and use persistent private storage. Do not expose uploaded documents through a public web directory.

## Roles and financial rules

The application has exactly three panels. Owner controls branding, accounts, withdrawals, and oversight. Manager manages hostel operations and verifies student payment claims. Students can access only their own records. Backend authorization is mandatory for every protected route.

Only verified payments count as collections. Invoices preserve the amount charged for each month. Expenses, salary payments, advances, and owner withdrawals are stored separately. An owner withdrawal is not an operating expense. The dashboard's operating position is verified collections less operating expenses and is not represented as a reconciled cash or bank balance.

## Automated reporting

The scheduler creates the previous calendar month's `.xlsx` report on the 5th at 09:00 in the configured hostel time zone. It records each generation and email attempt. SMTP must be configured to send email; without SMTP, the workbook is still generated and delivery is recorded as unavailable. Owners can also request a report from the UI.

## API and data

The API is rooted at `/api`. JSON errors use HTTP status codes and safe messages. Session authentication uses an HttpOnly session cookie and a CSRF token cookie. Database schema changes live in `backend/src/main/resources/db/migration/`. See `docs/API.md` for route groups and `docs/ACCOUNTING.md` for calculation definitions.

## Operations

Back up MySQL and the private upload/report directory together. See `docs/BACKUP.md` for backup, restore, and retention procedures. Health and scheduled report status are visible to authorized staff.

## Logo

Branding is stored centrally and can be changed by the Owner under Settings. The logo seen in the original message was not included as a file beside the text brief; upload the original PNG, JPEG, or WebP from Owner Settings to persist it. Until then the interface shows a controlled typographic fallback, never a substitute logo.
