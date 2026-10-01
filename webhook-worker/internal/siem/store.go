// SPDX-License-Identifier: AGPL-3.0-or-later
package siem

import (
	"context"
	"database/sql"
	"encoding/json"
	"time"

	_ "github.com/lib/pq"
)

// Delivery is a row from siem_deliveries joined with its connector.
type Delivery struct {
	ID           string
	ConnectorID  string
	EventType    string
	Payload      json.RawMessage
	Attempts     int
	Provider     string
	ConfigJSON   string
}

type Store interface {
	FetchPending(ctx context.Context, limit int) ([]Delivery, error)
	MarkDelivered(ctx context.Context, id string, responseCode int) error
	MarkRetrying(ctx context.Context, id string, attempts int, responseCode int) error
	MarkFailed(ctx context.Context, id string, attempts int, responseCode int) error
	MarkConnectorError(ctx context.Context, connectorID string) error
	Close() error
}

type PostgresStore struct {
	db *sql.DB
}

func NewPostgresStore(dsn string) (*PostgresStore, error) {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(10)
	db.SetConnMaxLifetime(30 * time.Minute)
	if err := db.Ping(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return &PostgresStore{db: db}, nil
}

// NewPostgresStoreFromDB reuses an existing pool (shared with webhook outbox).
func NewPostgresStoreFromDB(db *sql.DB) *PostgresStore {
	return &PostgresStore{db: db}
}

func (s *PostgresStore) DB() *sql.DB {
	return s.db
}

func (s *PostgresStore) Close() error {
	return s.db.Close()
}

func (s *PostgresStore) FetchPending(ctx context.Context, limit int) ([]Delivery, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT d.id::text,
		       d.connector_id::text,
		       d.event_type,
		       d.payload,
		       d.attempt_count,
		       c.provider,
		       c.config::text
		  FROM siem_deliveries d
		  JOIN siem_connectors c ON c.id = d.connector_id
		 WHERE d.status IN ('pending', 'retrying')
		   AND c.status = 'connected'
		 ORDER BY d.created_at ASC
		 LIMIT $1
		 FOR UPDATE OF d SKIP LOCKED
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Delivery
	for rows.Next() {
		var d Delivery
		if err := rows.Scan(
			&d.ID, &d.ConnectorID, &d.EventType, &d.Payload,
			&d.Attempts, &d.Provider, &d.ConfigJSON,
		); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (s *PostgresStore) MarkDelivered(ctx context.Context, id string, responseCode int) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE siem_deliveries
		   SET status = 'delivered', delivered_at = NOW(), last_response_code = $2
		 WHERE id = $1::uuid
	`, id, responseCode)
	return err
}

func (s *PostgresStore) MarkRetrying(ctx context.Context, id string, attempts int, responseCode int) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE siem_deliveries
		   SET status = 'retrying', attempt_count = $2, last_response_code = $3
		 WHERE id = $1::uuid
	`, id, attempts, responseCode)
	return err
}

func (s *PostgresStore) MarkFailed(ctx context.Context, id string, attempts int, responseCode int) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE siem_deliveries
		   SET status = 'failed', attempt_count = $2, last_response_code = $3
		 WHERE id = $1::uuid
	`, id, attempts, responseCode)
	return err
}

func (s *PostgresStore) MarkConnectorError(ctx context.Context, connectorID string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE siem_connectors
		   SET status = 'error'
		 WHERE id = $1::uuid
		   AND status = 'connected'
	`, connectorID)
	return err
}
