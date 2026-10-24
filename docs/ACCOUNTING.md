# Accounting definitions

Amounts use MySQL `DECIMAL(12,2)` and Java `BigDecimal`.

- **Fee obligation:** a monthly invoice's stored `fee_amount`. Each student and billing month is unique. Changing a student's current fee does not update earlier invoices.
- **Verified amount paid:** sum of payment submissions with status `APPROVED` for the invoice, less approved payments subsequently reversed. Pending and rejected submissions do not affect collections or invoice balances.
- **Invoice balance:** `fee_amount - paid_amount`. Approval locks the payment and invoice rows, checks the remaining amount, updates both, writes the collection ledger entry, and issues a unique receipt in one database transaction.
- **Verified fee collections:** sum of approved payment submissions in the selected date range. Reversed submissions have status `REVERSED` and are excluded.
- **Operating expenses:** non-reversed rows in `expenses`. Salary payments are written to this table under the protected `Employee salaries` category. Employee advances are not expenses when issued; their issue and repayment are tracked separately.
- **Owner withdrawals:** separately recorded cash movements. They are excluded from operating expenses.
- **Operating position shown on the dashboard:** this month's verified fee collections minus this month's recorded operating expenses. It is not a cash-on-hand or bank balance and excludes owner withdrawals, advance movements, opening balances, and other income.
- **Outstanding fees:** sum of positive invoice balances across all periods. **Overdue fees:** sum of positive balances where the invoice due date has passed.
- **Available beds:** sum of active room capacities minus active assignment rows. Bed uniqueness and capacity are enforced when creating an assignment.

Ledger sources link each collection, expense, withdrawal, salary expense, advance issue, and advance repayment to its source record. Reversals retain the original row, mark the linked ledger entry reversed, and write an audit event with a reason.
