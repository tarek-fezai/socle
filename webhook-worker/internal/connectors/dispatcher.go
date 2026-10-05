// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package connectors

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/socle-eu/socle-webhook-worker/internal/config"
	"github.com/socle-eu/socle-webhook-worker/internal/outbox"
)

type Dispatcher struct {
	cfg    config.Config
	logger *slog.Logger
	client *http.Client
}

func NewDispatcher(cfg config.Config, logger *slog.Logger) *Dispatcher {
	return &Dispatcher{
		cfg:    cfg,
		logger: logger,
		client: &http.Client{Timeout: 15 * time.Second},
	}
}

func (d *Dispatcher) Dispatch(ctx context.Context, event outbox.Event) (int, error) {
	var lastCode int
	var errs []error

	if d.cfg.SplunkHECURL != "" && d.cfg.SplunkHECToken != "" {
		code, err := d.sendSplunk(ctx, event)
		lastCode = code
		if err != nil {
			errs = append(errs, fmt.Errorf("splunk: %w", err))
		}
	}
	if d.cfg.DatadogAPIKey != "" {
		code, err := d.sendDatadog(ctx, event)
		lastCode = code
		if err != nil {
			errs = append(errs, fmt.Errorf("datadog: %w", err))
		}
	}
	if d.cfg.SentinelURL != "" && d.cfg.SentinelKey != "" {
		code, err := d.sendSentinel(ctx, event)
		lastCode = code
		if err != nil {
			errs = append(errs, fmt.Errorf("sentinel: %w", err))
		}
	}

	if len(errs) == 0 {
		d.logger.Info("event dispatched (no connectors configured or all ok)",
			"delivery_id", event.ID, "type", event.EventType)
		if lastCode == 0 {
			lastCode = 200
		}
		return lastCode, nil
	}
	return lastCode, errs[0]
}

func (d *Dispatcher) sendSplunk(ctx context.Context, event outbox.Event) (int, error) {
	body, _ := json.Marshal(map[string]any{
		"event":      json.RawMessage(event.Payload),
		"sourcetype": "socle:audit",
		"source":     "socle-webhook-worker",
		"fields": map[string]string{
			"delivery_id": event.ID,
			"event_type":  event.EventType,
		},
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.cfg.SplunkHECURL, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Authorization", "Splunk "+d.cfg.SplunkHECToken)
	req.Header.Set("Content-Type", "application/json")
	return d.do(req)
}

func (d *Dispatcher) sendDatadog(ctx context.Context, event outbox.Event) (int, error) {
	body, _ := json.Marshal([]map[string]any{{
		"message":  string(event.Payload),
		"ddsource": "socle",
		"service":  "socle-webhook-worker",
		"status":   "info",
		"attributes": map[string]string{
			"delivery_id": event.ID,
			"event_type":  event.EventType,
		},
	}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.cfg.DatadogURL, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("DD-API-KEY", d.cfg.DatadogAPIKey)
	req.Header.Set("Content-Type", "application/json")
	return d.do(req)
}

func (d *Dispatcher) sendSentinel(ctx context.Context, event outbox.Event) (int, error) {
	body, _ := json.Marshal([]map[string]any{{
		"DeliveryId":    event.ID,
		"EventType":     event.EventType,
		"EndpointId":    event.EndpointID,
		"Payload":       json.RawMessage(event.Payload),
		"TimeGenerated": time.Now().UTC().Format(time.RFC3339),
	}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.cfg.SentinelURL, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", d.cfg.SentinelKey)
	req.Header.Set("Log-Type", "SocleAudit")
	return d.do(req)
}

func (d *Dispatcher) do(req *http.Request) (int, error) {
	resp, err := d.client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	if resp.StatusCode >= 300 {
		return resp.StatusCode, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	return resp.StatusCode, nil
}
