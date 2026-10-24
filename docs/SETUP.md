# Local setup

## Database

Create an empty MySQL 8 database and a least-privilege application user:

```sql
CREATE DATABASE indus_hostel CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'indus_app'@'localhost' IDENTIFIED BY 'set-a-long-local-secret';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON indus_hostel.* TO 'indus_app'@'localhost';
```

Flyway creates the tables on API startup. The application user should not have global grants or access to other databases.

## Configure and start

1. Copy `backend/.env.example` to a secure local configuration source. Spring Boot does not load `.env` files automatically; set those variables in your shell or IDE configuration. Do not commit the populated file.
2. Set `SPRING_PROFILES_ACTIVE=dev` for local development. Keep `APP_SEED_DEMO=false` unless you want fictional sample accounts and a sample student.
3. Run the API with Java 21 and Maven 3.9+: `cd backend; mvn spring-boot:run`.
4. Run `cd frontend; npm ci; npm run dev`.
5. Open `http://localhost:5173`.

During local development, Vite proxies `/api` to `http://127.0.0.1:8080`, so browser session cookies stay same-origin. The default app accepts both `localhost:5173` and `127.0.0.1:5173`. Set `VITE_API_URL` only when you intentionally use a separate API origin, and keep `APP_FRONTEND_ORIGIN` aligned with the frontend URL.

## Docker Compose

1. Copy `.env.compose.example` to `.env` in the project root and replace both database passwords with separate strong values.
2. Start the stack with `docker compose up --build -d`.
3. Open `http://localhost:8081`; the API and MySQL are available only to the Compose network.

For a public deployment, set `APP_SESSION_SECURE=true`, use HTTPS, configure the Owner email and SMTP settings, and preserve the MySQL, private-file, and report volumes during backup and restore.

## Fictional demo accounts

Demo seeding runs only when both the `dev` profile and `APP_SEED_DEMO=true` are set, and only if the database has no users. The seeded people and records are fictional.

| Panel | Email | Initial password |
|---|---|---|
| Owner | `demo-owner@example.test` | `LocalDemo-Owner!2026` |
| Manager | `demo-manager@example.test` | `LocalDemo-Manager!2026` |
| Student | `demo-student@example.test` | `LocalDemo-Student!2026` |

These credentials are for local development only. Disable demo seeding and create production accounts through a controlled provisioning process before deployment.

## Email and reporting

Set the Owner email in Owner Settings. To deliver the monthly workbook and password reset links, configure `SPRING_MAIL_HOST`, port, username, password, and TLS settings. A scheduled report can still be generated without SMTP; its delivery status will say `NOT_CONFIGURED` or `FAILED`, never `SENT`.

The report job uses `APP_HOSTEL_TIMEZONE`. On the 5th at 09:00 it creates the previous calendar month's workbook and tries to email it. Retries run at noon and 15:00 if delivery fails. The report history and delivery errors are visible in the Owner panel.

## Logo and files

The supplied chat image was not available as a file in the project attachment directory. Upload the original official logo in Owner Settings. The API stores it privately and serves it from an authenticated/public-branding endpoint with an image content type. Student photos, payment proof, and bills remain behind authenticated API access. Uploads are limited to 8 MB and PNG, JPEG, WebP, or PDF with signature checks.
