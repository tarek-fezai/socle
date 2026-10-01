// SPDX-License-Identifier: AGPL-3.0-or-later
package siem

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

// MaxAttempts is the number of poll-cycle failures before a delivery is marked failed
// and its connector is set to status=error (same order of magnitude as webhook in-tick retries).
const MaxAttempts = 5

var deliveryFailures = promauto.NewCounterVec(prometheus.CounterOpts{
	Name: "socle_siem_delivery_failures",
	Help: "SIEM delivery failures by connector (after a failed dispatch cycle)",
}, []string{"connector_id", "provider"})

// HTTPDispatcher posts a delivery to the connector endpoint.
type HTTPDispatcher interface {
	Dispatch(ctx context.Context, delivery Delivery) (int, error)
}

type Consumer struct {
	store          Store
	dispatcher     HTTPDispatcher
	logger         *slog.Logger
	pollInterval   time.Duration
	batchSize      int
	maxAttempts    int
	inTickRetries  int
	initialBackoff time.Duration
}

func NewConsumer(store Store, dispatcher HTTPDispatcher, logger *slog.Logger, pollInterval time.Duration, batchSize int) *Consumer {
	return &Consumer{
		store:          store,
		dispatcher:     dispatcher,
		logger:         logger,
		pollInterval:   pollInterval,
		batchSize:      batchSize,
		maxAttempts:    MaxAttempts,
		inTickRetries:  5,
		initialBackoff: 500 * time.Millisecond,
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
	deliveries, err := c.store.FetchPending(ctx, c.batchSize)
	if err != nil {
		c.logger.Error("fetch siem_deliveries failed", "error", err)
		return
	}
	for _, d := range deliveries {
		c.process(ctx, d)
	}
}

func (c *Consumer) process(ctx context.Context, d Delivery) {
	code, err := c.dispatchWithRetry(ctx, d)
	if err == nil {
		if markErr := c.store.MarkDelivered(ctx, d.ID, code); markErr != nil {
			c.logger.Error("mark siem delivered failed", "delivery_id", d.ID, "error", markErr)
		}
		return
	}

	attempts := d.Attempts + 1
	deliveryFailures.WithLabelValues(d.ConnectorID, d.Provider).Inc()
	c.logger.Error("siem dispatch failed",
		"delivery_id", d.ID,
		"connector_id", d.ConnectorID,
		"attempts", attempts,
		"error", err,
	)

	if attempts >= c.maxAttempts {
		_ = c.store.MarkFailed(ctx, d.ID, attempts, code)
		if markErr := c.store.MarkConnectorError(ctx, d.ConnectorID); markErr != nil {
			c.logger.Error("mark connector error failed", "connector_id", d.ConnectorID, "error", markErr)
		} else {
			c.logger.Warn("siem connector marked error after repeated failures",
				"connector_id", d.ConnectorID,
				"attempts", attempts,
			)
		}
		return
	}
	_ = c.store.MarkRetrying(ctx, d.ID, attempts, code)
}

// Same exponential backoff pattern as webhook outbox.Consumer.dispatchWithRetry.
func (c *Consumer) dispatchWithRetry(ctx context.Context, d Delivery) (int, error) {
	var lastErr error
	var lastCode int
	backoff := c.initialBackoff
	retries := c.inTickRetries
	if retries < 1 {
		retries = 1
	}
	for attempt := 0; attempt < retries; attempt++ {
		lastCode, lastErr = c.dispatcher.Dispatch(ctx, d)
		if lastErr == nil {
			return lastCode, nil
		}
		if attempt == retries-1 {
			break
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
