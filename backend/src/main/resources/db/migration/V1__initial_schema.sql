CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(190) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  role ENUM('OWNER','MANAGER','STUDENT') NOT NULL,
  student_id BIGINT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user_role(role)
);
CREATE TABLE password_reset_tokens (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_password_reset_user FOREIGN KEY(user_id) REFERENCES users(id),
  INDEX idx_password_reset_expiry(user_id,expires_at)
);
CREATE TABLE branding_settings (
  id TINYINT PRIMARY KEY DEFAULT 1,
  hostel_name VARCHAR(160) NOT NULL,
  logo_file_id BIGINT NULL,
  owner_email VARCHAR(190) NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
INSERT INTO branding_settings(id, hostel_name) VALUES(1, 'INDUS HOSTEL SERVICE BADIN');
CREATE TABLE rooms (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_number VARCHAR(40) NOT NULL UNIQUE,
  section_name VARCHAR(100) NULL,
  capacity INT NOT NULL,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  notes VARCHAR(1000) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK(capacity > 0)
);
CREATE TABLE students (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_code VARCHAR(40) NOT NULL UNIQUE,
  full_name VARCHAR(160) NOT NULL,
  contact VARCHAR(40) NOT NULL,
  emergency_contact VARCHAR(160) NULL,
  address VARCHAR(500) NULL,
  registration_date DATE NOT NULL,
  check_in_date DATE NULL,
  check_out_date DATE NULL,
  monthly_fee DECIMAL(12,2) NOT NULL,
  due_day TINYINT NOT NULL DEFAULT 5,
  status ENUM('ACTIVE','CHECKED_OUT','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  notes VARCHAR(1000) NULL,
  photo_file_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CHECK(monthly_fee >= 0), CHECK(due_day BETWEEN 1 AND 28), INDEX idx_student_name(full_name), INDEX idx_student_status(status)
);
ALTER TABLE users ADD CONSTRAINT fk_user_student FOREIGN KEY(student_id) REFERENCES students(id);
CREATE TABLE student_assignments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT NOT NULL,
  room_id BIGINT NOT NULL,
  bed_number VARCHAR(20) NOT NULL,
  started_at DATE NOT NULL,
  ended_at DATE NULL,
  active_bed VARCHAR(20) GENERATED ALWAYS AS (CASE WHEN ended_at IS NULL THEN bed_number ELSE NULL END) STORED,
  created_by BIGINT NOT NULL,
  CONSTRAINT fk_assignment_student FOREIGN KEY(student_id) REFERENCES students(id),
  CONSTRAINT fk_assignment_room FOREIGN KEY(room_id) REFERENCES rooms(id),
  CONSTRAINT fk_assignment_user FOREIGN KEY(created_by) REFERENCES users(id),
  CONSTRAINT uq_active_bed UNIQUE(room_id, active_bed),
  INDEX idx_assignment_student_active(student_id, ended_at)
);
CREATE TABLE fee_invoices (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT NOT NULL,
  billing_month DATE NOT NULL,
  fee_amount DECIMAL(12,2) NOT NULL,
  paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  due_date DATE NOT NULL,
  status ENUM('UNPAID','PENDING_VERIFICATION','PARTIALLY_PAID','PAID','OVERDUE') NOT NULL DEFAULT 'UNPAID',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_invoice_student FOREIGN KEY(student_id) REFERENCES students(id),
  CONSTRAINT uq_invoice_month UNIQUE(student_id, billing_month),
  CHECK(fee_amount >= 0 AND paid_amount >= 0 AND paid_amount <= fee_amount),
  INDEX idx_invoice_month_status(billing_month,status), INDEX idx_invoice_due(due_date,status)
);
CREATE TABLE files (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  storage_key VARCHAR(80) NOT NULL UNIQUE,
  original_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(80) NOT NULL,
  byte_size BIGINT NOT NULL,
  uploaded_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_file_user FOREIGN KEY(uploaded_by) REFERENCES users(id)
);
ALTER TABLE students ADD CONSTRAINT fk_student_photo FOREIGN KEY(photo_file_id) REFERENCES files(id);
ALTER TABLE branding_settings ADD CONSTRAINT fk_branding_logo FOREIGN KEY(logo_file_id) REFERENCES files(id);
CREATE TABLE payment_submissions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  invoice_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  payment_date DATE NOT NULL,
  method VARCHAR(60) NULL,
  reference VARCHAR(120) NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  proof_file_id BIGINT NULL,
  status ENUM('PENDING','APPROVED','REJECTED','REVERSED') NOT NULL DEFAULT 'PENDING',
  rejection_reason VARCHAR(500) NULL,
  submitted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_by BIGINT NULL,
  reviewed_at TIMESTAMP NULL,
  CONSTRAINT fk_payment_invoice FOREIGN KEY(invoice_id) REFERENCES fee_invoices(id),
  CONSTRAINT fk_payment_student FOREIGN KEY(student_id) REFERENCES students(id),
  CONSTRAINT fk_payment_proof FOREIGN KEY(proof_file_id) REFERENCES files(id),
  CONSTRAINT fk_payment_reviewer FOREIGN KEY(reviewed_by) REFERENCES users(id),
  CHECK(amount > 0), CONSTRAINT uq_payment_idempotency UNIQUE(student_id,idempotency_key), CONSTRAINT uq_payment_reference UNIQUE(student_id,reference), INDEX idx_payment_status(status,submitted_at)
);
CREATE TABLE receipts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  receipt_number VARCHAR(40) NOT NULL UNIQUE,
  payment_submission_id BIGINT NOT NULL UNIQUE,
  previous_balance DECIMAL(12,2) NOT NULL DEFAULT 0,
  remaining_balance DECIMAL(12,2) NOT NULL DEFAULT 0,
  issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  issued_by BIGINT NOT NULL,
  voided_at TIMESTAMP NULL,
  reversal_reason VARCHAR(500) NULL,
  CONSTRAINT fk_receipt_payment FOREIGN KEY(payment_submission_id) REFERENCES payment_submissions(id),
  CONSTRAINT fk_receipt_user FOREIGN KEY(issued_by) REFERENCES users(id)
);
CREATE TABLE expense_categories (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL UNIQUE,
  active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO expense_categories(name) VALUES ('Grocery'),('Sabzi / Vegetables'),('Electricity'),('Gas'),('Water'),('Wi-Fi / Internet'),('Hostel rent'),('Shop fees'),('Other expenses'),('Employee salaries'),('Employee advances');
CREATE TABLE expenses (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  category_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  expense_date DATE NOT NULL,
  description VARCHAR(500) NOT NULL,
  vendor VARCHAR(160) NULL,
  payment_method VARCHAR(60) NULL,
  bill_file_id BIGINT NULL,
  created_by BIGINT NOT NULL,
  updated_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  reversed_at TIMESTAMP NULL,
  reversal_reason VARCHAR(500) NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  CONSTRAINT fk_expense_category FOREIGN KEY(category_id) REFERENCES expense_categories(id),
  CONSTRAINT fk_expense_file FOREIGN KEY(bill_file_id) REFERENCES files(id),
  CONSTRAINT fk_expense_user FOREIGN KEY(created_by) REFERENCES users(id),
  CHECK(amount > 0), CONSTRAINT uq_expense_idempotency UNIQUE(created_by,idempotency_key), INDEX idx_expense_date(expense_date)
);
CREATE TABLE recurring_bills (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  category VARCHAR(80) NOT NULL,
  vendor VARCHAR(160) NULL,
  expected_amount DECIMAL(12,2) NULL,
  frequency ENUM('MONTHLY','QUARTERLY','YEARLY') NOT NULL DEFAULT 'MONTHLY',
  next_due_date DATE NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  notes VARCHAR(500) NULL,
  idempotency_key VARCHAR(80) NOT NULL UNIQUE
);
CREATE TABLE bill_payments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  recurring_bill_id BIGINT NULL,
  category VARCHAR(80) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  due_date DATE NOT NULL,
  payment_date DATE NULL,
  status ENUM('UPCOMING','DUE','OVERDUE','PAID') NOT NULL DEFAULT 'UPCOMING',
  bill_file_id BIGINT NULL,
  notes VARCHAR(500) NULL,
  recorded_by BIGINT NOT NULL,
  CONSTRAINT fk_bill_schedule FOREIGN KEY(recurring_bill_id) REFERENCES recurring_bills(id),
  CONSTRAINT fk_bill_file FOREIGN KEY(bill_file_id) REFERENCES files(id),
  CONSTRAINT fk_bill_user FOREIGN KEY(recorded_by) REFERENCES users(id),
  CHECK(amount >= 0), CONSTRAINT uq_recurring_bill_due UNIQUE(recurring_bill_id,due_date), INDEX idx_bill_due(due_date,status)
);
CREATE TABLE employees (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_code VARCHAR(40) NOT NULL UNIQUE,
  full_name VARCHAR(160) NOT NULL,
  title VARCHAR(100) NOT NULL,
  contact VARCHAR(40) NULL,
  joining_date DATE NOT NULL,
  salary_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  notes VARCHAR(500) NULL,
  CHECK(salary_amount >= 0)
);
CREATE TABLE salary_obligations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  salary_period DATE NOT NULL,
  amount_due DECIMAL(12,2) NOT NULL,
  amount_paid DECIMAL(12,2) NOT NULL DEFAULT 0,
  CONSTRAINT fk_salary_obligation_employee FOREIGN KEY(employee_id) REFERENCES employees(id),
  CONSTRAINT uq_salary_period UNIQUE(employee_id,salary_period),
  CHECK(amount_due >= 0 AND amount_paid >= 0 AND amount_paid <= amount_due)
);
CREATE TABLE salary_payments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  obligation_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  payment_date DATE NOT NULL,
  notes VARCHAR(500) NULL,
  recorded_by BIGINT NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  CONSTRAINT fk_salary_payment_obligation FOREIGN KEY(obligation_id) REFERENCES salary_obligations(id),
  CONSTRAINT fk_salary_user FOREIGN KEY(recorded_by) REFERENCES users(id),
  CONSTRAINT uq_salary_payment_idempotency UNIQUE(recorded_by,idempotency_key),
  CHECK(amount > 0)
);
CREATE TABLE employee_advances (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  employee_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  issued_date DATE NOT NULL,
  reason VARCHAR(500) NOT NULL,
  repaid_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  recorded_by BIGINT NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  CONSTRAINT fk_advance_employee FOREIGN KEY(employee_id) REFERENCES employees(id),
  CONSTRAINT fk_advance_user FOREIGN KEY(recorded_by) REFERENCES users(id),
  CONSTRAINT uq_advance_idempotency UNIQUE(recorded_by,idempotency_key),
  CHECK(amount > 0 AND repaid_amount >= 0 AND repaid_amount <= amount)
);
CREATE TABLE owner_withdrawals (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  amount DECIMAL(12,2) NOT NULL,
  withdrawal_date DATE NOT NULL,
  reason VARCHAR(500) NOT NULL,
  method VARCHAR(60) NULL,
  note VARCHAR(500) NULL,
  recorded_by BIGINT NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_withdrawal_user FOREIGN KEY(recorded_by) REFERENCES users(id),
  CONSTRAINT uq_withdrawal_idempotency UNIQUE(recorded_by,idempotency_key),
  CHECK(amount > 0)
);
CREATE TABLE ledger_entries (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  entry_type ENUM('FEE_COLLECTION','EXPENSE','OWNER_WITHDRAWAL','SALARY_PAYMENT','ADVANCE_ISSUED','ADVANCE_REPAYMENT','ADJUSTMENT') NOT NULL,
  direction ENUM('IN','OUT') NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_id BIGINT NOT NULL,
  occurred_on DATE NOT NULL,
  recorded_by BIGINT NOT NULL,
  description VARCHAR(500) NOT NULL,
  reversed_at TIMESTAMP NULL,
  reversal_reason VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_ledger_source UNIQUE(source_type,source_id),
  CONSTRAINT fk_ledger_user FOREIGN KEY(recorded_by) REFERENCES users(id),
  CHECK(amount > 0), INDEX idx_ledger_date(occurred_on,entry_type)
);
CREATE TABLE advance_transactions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  advance_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  transaction_date DATE NOT NULL,
  kind ENUM('ISSUED','REPAYMENT') NOT NULL,
  note VARCHAR(500) NULL,
  recorded_by BIGINT NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  CONSTRAINT fk_advance_tx_advance FOREIGN KEY(advance_id) REFERENCES employee_advances(id),
  CONSTRAINT fk_advance_tx_user FOREIGN KEY(recorded_by) REFERENCES users(id),
  CONSTRAINT uq_advance_transaction_idempotency UNIQUE(recorded_by,idempotency_key),
  CHECK(amount > 0)
);
CREATE TABLE notifications (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  kind VARCHAR(60) NOT NULL,
  title VARCHAR(160) NOT NULL,
  message VARCHAR(500) NOT NULL,
  related_type VARCHAR(40) NULL,
  related_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  read_at TIMESTAMP NULL,
  dedupe_key VARCHAR(190) NULL,
  CONSTRAINT fk_notification_user FOREIGN KEY(user_id) REFERENCES users(id),
  CONSTRAINT uq_notification_dedupe UNIQUE(user_id,dedupe_key),
  INDEX idx_notification_user(user_id,created_at)
);
CREATE TABLE audit_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_user_id BIGINT NOT NULL,
  action VARCHAR(80) NOT NULL,
  entity_type VARCHAR(60) NOT NULL,
  entity_id BIGINT NULL,
  reason VARCHAR(500) NULL,
  details JSON NULL,
  occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_audit_user FOREIGN KEY(actor_user_id) REFERENCES users(id),
  INDEX idx_audit_time(occurred_at), INDEX idx_audit_entity(entity_type,entity_id)
);
CREATE TABLE monthly_reports (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reporting_month DATE NOT NULL,
  generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  file_key VARCHAR(100) NOT NULL,
  generation_kind ENUM('SCHEDULED','MANUAL') NOT NULL,
  generated_by BIGINT NULL,
  delivery_status ENUM('NOT_CONFIGURED','PENDING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
  delivery_error VARCHAR(1000) NULL,
  delivered_at TIMESTAMP NULL,
  CONSTRAINT fk_report_user FOREIGN KEY(generated_by) REFERENCES users(id),
  CONSTRAINT uq_report_period_kind UNIQUE(reporting_month,generation_kind),
  INDEX idx_report_month(reporting_month)
);
CREATE TABLE email_delivery_attempts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_id BIGINT NOT NULL,
  attempted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status ENUM('SENT','FAILED','NOT_CONFIGURED') NOT NULL,
  error_message VARCHAR(1000) NULL,
  CONSTRAINT fk_delivery_report FOREIGN KEY(report_id) REFERENCES monthly_reports(id),
  INDEX idx_delivery_report(report_id,attempted_at)
);
CREATE TABLE scheduled_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_key VARCHAR(120) NOT NULL UNIQUE,
  status ENUM('RUNNING','SUCCEEDED','FAILED') NOT NULL,
  started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at TIMESTAMP NULL,
  details VARCHAR(1000) NULL
);
