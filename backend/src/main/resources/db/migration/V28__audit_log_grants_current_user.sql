-- SPDX-License-Identifier: AGPL-3.0-or-later
-- V5 hardcodait le rôle "socle". Si l'instance tourne sous un autre rôle
-- (ex. socle_app), aligner les grants append-only sur current_user.
DO $$
BEGIN
  EXECUTE format('REVOKE UPDATE, DELETE ON TABLE audit_log_events FROM %I', current_user);
  EXECUTE format('GRANT SELECT, INSERT ON TABLE audit_log_events TO %I', current_user);
  EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE audit_log_events_id_seq TO %I', current_user);
EXCEPTION
  WHEN undefined_object THEN
    NULL;
  WHEN insufficient_privilege THEN
    NULL;
END $$;
