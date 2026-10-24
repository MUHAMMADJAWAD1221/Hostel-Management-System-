# Backup and recovery

The application does not claim a backup is healthy until the operator has scheduled and verified the backup process. Back up the database, uploaded files, and generated report files as one recovery set.

## Windows backup script

`ops/backup.ps1` writes a timestamped snapshot directory containing a transactional `mysqldump`, private uploads, and generated workbooks. Configure these environment variables for the scheduled task:

- `MYSQLDUMP_PATH`: path to `mysqldump.exe`
- `MYSQL_DEFAULTS_FILE`: path to a MySQL option file with `[client]`, `user`, `password`, and `host` (restrict its ACL to the service account)
- `APP_BACKUP_ROOT`: backup destination on encrypted storage or an encrypted volume
- `APP_FILE_STORAGE`: the API private file directory
- `APP_REPORT_STORAGE`: the API report directory

Run once manually as the service account and inspect the created files before creating a Task Scheduler job. Schedule daily off peak and set a separate, documented retention policy on the backup destination. Copy a second encrypted set off-host. The script does not delete old backups.

## Restore procedure

1. Stop the API and scheduled task. Select a complete snapshot with its SQL dump, `storage` folder, and `reports` folder.
2. Restore the SQL dump into a controlled MySQL instance using the same schema/database name. Example in `cmd.exe`:

   ```text
   mysql.exe --defaults-extra-file="C:\secure\mysql-backup.cnf" indus_hostel < "D:\backups\snapshot\database.sql"
   ```

3. Restore the snapshot `storage` and `reports` directories to the paths configured by `APP_FILE_STORAGE` and `APP_REPORT_STORAGE`. Preserve the service account ACLs.
4. Start the API and confirm health, branding, file retrieval, report history, student records, and payment-to-ledger totals before resuming service.

Run a restore drill to an isolated database and file directory before relying on backups. No restore drill was possible in this workspace because MySQL and Docker are not installed.
