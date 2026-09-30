-- Append-only réel sur audit_log_events : le rôle applicatif (socle) ne peut plus
-- UPDATE ni DELETE. INSERT + SELECT conservés.
-- Note : le propriétaire de table peut toujours ALTER ; les DML UPDATE/DELETE sont révoqués.

REVOKE UPDATE, DELETE ON TABLE audit_log_events FROM PUBLIC;
REVOKE UPDATE, DELETE ON TABLE audit_log_events FROM socle;

GRANT SELECT, INSERT ON TABLE audit_log_events TO socle;
GRANT USAGE, SELECT ON SEQUENCE audit_log_events_id_seq TO socle;
