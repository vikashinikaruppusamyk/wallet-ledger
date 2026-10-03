-- One-time setup for running the app and tests against a locally installed PostgreSQL
-- (docker compose does the equivalent automatically). Run as a superuser:
--   psql -U postgres -h localhost -f db/local-setup.sql
CREATE ROLE wallet LOGIN PASSWORD 'wallet';
CREATE DATABASE wallet OWNER wallet;
CREATE DATABASE wallet_test OWNER wallet;
-- dblink powers the scripted two-session isolation lab (docs/isolation.md).
\connect wallet
CREATE EXTENSION IF NOT EXISTS dblink;
