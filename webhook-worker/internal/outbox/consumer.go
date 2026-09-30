package outbox

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	_ "github.com/lib/pq"
)

// Event mirrors webhook_deliveries (Flyway V1__init.sql) for SIEM/webhook fan-out.
type Event struct {
	ID         string
	EndpointID string
	EventType  string
	Payload    json.RawMessage
	Attempts   int
}

type Store interface {
	FetchUnpublished(ctx context.Context, limit int) ([]Event, error)
	MarkPublished(ctx context.Context, id string, responseCode int) error
	MarkFailed(ctx context.Context, id string, attempts int, responseCode int) error
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

func (s *PostgresStore) Close() error {
	return s.db.Close()
}

// DB exposes the shared pool for sibling consumers (e.g. SIEM).
func (s *PostgresStore) DB() *sql.DB {
	return s.db
}

func (s *PostgresStore) FetchUnpublished(ctx context.Context, limit int) ([]Event, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id::text, endpoint_id::text, event_type, payload, attempt_count
		FROM webhook_deliveries
		WHERE status IN ('pending', 'retrying')
		ORDER BY created_at ASC
		LIMIT $1
		FOR UPDATE SKIP LOCKED
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var events []Event
	for rows.Next() {
		var e Event
		if err := rows.Scan(&e.ID, &e.EndpointID, &e.EventType, &e.Payload, &e.Attempts); err != nil {
			return nil, err
		}
		events = append(events, e)
	}
	return events, rows.Err()
}

func (s *PostgresStore) MarkPublished(ctx context.Context, id string, responseCode int) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE webhook_deliveries
		SET status = 'delivered', delivered_at = NOW(), last_response_code = $2
		WHERE id = $1::uuid
	`, id, responseCode)
	return err
}

func (s *PostgresStore) MarkFailed(ctx context.Context, id string, attempts int, responseCode int) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE webhook_deliveries
		SET status = 'retrying', attempt_count = $2, last_response_code = $3
		WHERE id = $1::uuid
	`, id, attempts, responseCode)
	return err
}

type Dispatcher interface {
	Dispatch(ctx context.Context, event Event) (responseCode int, err error)
}

type Consumer struct {
	store        Store
	dispatcher   Dispatcher
	logger       *slog.Logger
	pollInterval time.Duration
	batchSize    int
}

func NewConsumer(store Store, dispatcher Dispatcher, logger *slog.Logger, pollInterval time.Duration, batchSize int) *Consumer {
	return &Consumer{
		store:        store,
		dispatcher:   dispatcher,
		logger:       logger,
		pollInterval: pollInterval,
		batchSize:    batchSize,
	}
}

func (c *Consumer) Run(ctx context.Context) {
	ticker := time.NewTicker(c.pollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			c.tick(ctx)
		}
	}
}

func (c *Consumer) tick(ctx context.Context) {
	events, err := c.store.FetchUnpublished(ctx, c.batchSize)
	if err != nil {
		c.logger.Error("fetch webhook_deliveries failed", "error", err)
		return
	}
	for _, event := range events {
		code, err := c.dispatchWithRetry(ctx, event)
		if err != nil {
			attempts := event.Attempts + 1
			_ = c.store.MarkFailed(ctx, event.ID, attempts, code)
			c.logger.Error("dispatch failed", "delivery_id", event.ID, "attempts", attempts, "error", err)
			continue
		}
		if err := c.store.MarkPublished(ctx, event.ID, code); err != nil {
			c.logger.Error("mark delivered failed", "delivery_id", event.ID, "error", err)
		}
	}
}

func (c *Consumer) dispatchWithRetry(ctx context.Context, event Event) (int, error) {
	var lastErr error
	var lastCode int
	backoff := 500 * time.Millisecond
	for attempt := 0; attempt < 5; attempt++ {
		lastCode, lastErr = c.dispatcher.Dispatch(ctx, event)
		if lastErr == nil {
			return lastCode, nil
		}
		select {
		case <-ctx.Done():
			return lastCode, ctx.Err()
		case <-time.After(backoff):
			backoff *= 2
		}
	}
	return lastCode, fmt.Errorf("exhausted retries: %w", lastErr)
}
