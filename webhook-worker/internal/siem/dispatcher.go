package siem

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// ConnectorConfig is the JSONB shape stored in siem_connectors.config.
// Secrets (hec_token, api_key, shared_key) must never be logged.
type ConnectorConfig struct {
	Endpoint  string `json:"endpoint"`
	Format    string `json:"format"`
	HECToken  string `json:"hec_token"`
	Token     string `json:"token"`
	APIKey    string `json:"api_key"`
	DDAPIKey  string `json:"dd_api_key"`
	SharedKey string `json:"shared_key"`
	Auth      string `json:"authorization"`
}

func ParseConfig(raw string) (ConnectorConfig, error) {
	var c ConnectorConfig
	if strings.TrimSpace(raw) == "" {
		return c, nil
	}
	if err := json.Unmarshal([]byte(raw), &c); err != nil {
		return c, err
	}
	return c, nil
}

func (c ConnectorConfig) hecToken() string {
	if c.HECToken != "" {
		return c.HECToken
	}
	return c.Token
}

func (c ConnectorConfig) apiKey() string {
	if c.APIKey != "" {
		return c.APIKey
	}
	return c.DDAPIKey
}

func (c ConnectorConfig) sharedKey() string {
	if c.SharedKey != "" {
		return c.SharedKey
	}
	return c.Auth
}

type Dispatcher struct {
	logger *slog.Logger
	client *http.Client
}

func NewDispatcher(logger *slog.Logger) *Dispatcher {
	return &Dispatcher{
		logger: logger,
		client: &http.Client{Timeout: 15 * time.Second},
	}
}

// Dispatch POSTs the already-formatted payload to the connector endpoint.
func (d *Dispatcher) Dispatch(ctx context.Context, delivery Delivery) (int, error) {
	cfg, err := ParseConfig(delivery.ConfigJSON)
	if err != nil {
		return 0, fmt.Errorf("invalid connector config: %w", err)
	}
	if cfg.Endpoint == "" {
		return 0, fmt.Errorf("connector %s: missing config.endpoint", delivery.ConnectorID)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, cfg.Endpoint, bytes.NewReader(delivery.Payload))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")

	provider := strings.ToLower(delivery.Provider)
	switch provider {
	case "splunk":
		token := cfg.hecToken()
		if token == "" {
			return 0, fmt.Errorf("connector %s: missing hec_token", delivery.ConnectorID)
		}
		req.Header.Set("Authorization", "Splunk "+token)
	case "datadog":
		key := cfg.apiKey()
		if key == "" {
			return 0, fmt.Errorf("connector %s: missing api_key", delivery.ConnectorID)
		}
		req.Header.Set("DD-API-KEY", key)
	case "sentinel":
		key := cfg.sharedKey()
		if key == "" {
			return 0, fmt.Errorf("connector %s: missing shared_key", delivery.ConnectorID)
		}
		req.Header.Set("Authorization", key)
		req.Header.Set("Log-Type", "SocleAudit")
	default:
		return 0, fmt.Errorf("unsupported provider %q", delivery.Provider)
	}

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
