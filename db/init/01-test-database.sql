-- Runs once, when the compose database volume is first created.
CREATE DATABASE wallet_test OWNER wallet;
-- dblink powers the scripted two-session isolation lab (docs/isolation.md).
CREATE EXTENSION IF NOT EXISTS dblink;
