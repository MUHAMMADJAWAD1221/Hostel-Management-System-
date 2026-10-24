# Deployment outline

The included `docker-compose.yml` is a convenient single-host development deployment, not a hardened public production topology. It runs MySQL, the Spring API, and the Vite-built React application behind Nginx. It requires Docker Compose and environment values for the database root password, application password, Owner email, and public origin.

For production, use managed MySQL or a private database network, TLS termination at a trusted reverse proxy, persistent encrypted storage for uploads and reports, strict firewall rules, a secret manager, central logs/metrics, session/rate-limit storage appropriate for the replica count, and scheduled off-host encrypted backups. Set `APP_SESSION_SECURE=true`, choose a fixed trusted `APP_FRONTEND_ORIGIN`, disable `APP_SEED_DEMO`, set strong SMTP and database passwords, and configure SMTP before accepting real users.

Health is exposed at `/actuator/health`. The application log records scheduled job failures; monthly report delivery attempts are stored in MySQL. Configure alerting on health, failed report deliveries, and failed scheduled tasks. Backups must include MySQL and both persistent application volumes.
