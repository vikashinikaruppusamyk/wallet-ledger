-- Reference data every environment needs.

-- Back-office operator used for admin endpoints (releases, reversals, manual runs).
INSERT INTO users (name, email, phone, kyc_status, role)
VALUES ('Platform Admin', 'admin@wallet.local', '+910000000000', 'VERIFIED', 'ADMIN');

-- System accounts: they belong to no user and may go negative.
--   CASH_IN     is debited on every top-up (money arriving from banks)
--   CASH_OUT    is credited on every withdrawal (money leaving to banks)
--   FEE_REVENUE is credited with fees
INSERT INTO accounts (kind, system_code, name) VALUES
    ('SYSTEM', 'CASH_IN',     'System cash-in'),
    ('SYSTEM', 'CASH_OUT',    'System cash-out'),
    ('SYSTEM', 'FEE_REVENUE', 'System fee revenue');

INSERT INTO job_locks (job_name) VALUES ('nightly_reconciliation');

-- Fraud rules. Amounts in paise. Thresholds are data: tune with UPDATE, no deploy.
INSERT INTO risk_rules (code, description, action, applies_to, amount_threshold, count_threshold, window_seconds) VALUES
    ('LARGE_AMOUNT',
     'Single transfer at or above the threshold',
     'HOLD', ARRAY['PEER_TRANSFER', 'WITHDRAWAL'], 5000000, NULL, NULL),
    ('VELOCITY',
     'Too many outgoing transfers from one wallet within the window',
     'HOLD', ARRAY['PEER_TRANSFER', 'WITHDRAWAL'], NULL, 10, 60),
    ('DAILY_OUTFLOW',
     'Outgoing total (posted + held) in the window would exceed the threshold',
     'HOLD', ARRAY['PEER_TRANSFER', 'WITHDRAWAL'], 10000000, NULL, 86400),
    ('UNVERIFIED_KYC',
     'Sender has not completed KYC and the amount is at or above the threshold',
     'HOLD', ARRAY['PEER_TRANSFER', 'WITHDRAWAL'], 1000000, NULL, NULL),
    ('NEW_PAYEE_LARGE',
     'First ever transfer to this recipient at or above the threshold',
     'FLAG', ARRAY['PEER_TRANSFER'], 1000000, NULL, NULL);
