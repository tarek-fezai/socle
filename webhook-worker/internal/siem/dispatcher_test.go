package siem

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

type memStore struct {
	mu           sync.Mutex
	pending      []Delivery
	delivered    map[string]int
	retrying     map[string]int
	failed       map[string]int
	connectorErr map[string]bool
}

func newMemStore(d ...Delivery) *memStore {
	cp := make([]Delivery, len(d))
	copy(cp, d)
	return &memStore{
		pending:      cp,
		delivered:    map[string]int{},
		retrying:     map[string]int{},
		failed:       map[string]int{},
		connectorErr: map[string]bool{},
	}
}

func (m *memStore) FetchPending(ctx context.Context, limit int) ([]Delivery, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.pending) == 0 {
		return nil, nil
	}
	n := limit
	if n > len(m.pending) {
		n = len(m.pending)
	}
	out := append([]Delivery(nil), m.pending[:n]...)
	m.pending = m.pending[n:]
	return out, nil
}

func (m *memStore) MarkDelivered(ctx context.Context, id string, responseCode int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.delivered[id] = responseCode
	return nil
}

func (m *memStore) MarkRetrying(ctx context.Context, id string, attempts int, responseCode int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.retrying[id] = attempts
	return nil
}

func (m *memStore) MarkFailed(ctx context.Context, id string, attempts int, responseCode int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.failed[id] = attempts
	return nil
}

func (m *memStore) MarkConnectorError(ctx context.Context, connectorID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.connectorErr[connectorID] = true
	return nil
}

func (m *memStore) Close() error { return nil }

type stubDispatcher struct {
	code int
	err  error
	calls int
}

func (s *stubDispatcher) Dispatch(ctx context.Context, delivery Delivery) (int, error) {
	s.calls++
	return s.code, s.err
}

func TestDispatcher_SplunkHEC_Success(t *testing.T) {
	var gotAuth string
	var gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"text":"Success","code":0}`))
	}))
	defer srv.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := NewDispatcher(logger)
	cfg, _ := json.Marshal(map[string]string{
		"endpoint":  srv.URL,
		"format":    "hec",
		"hec_token": "secret-token",
	})
	code, err := d.Dispatch(context.Background(), Delivery{
		ID:          "d1",
		ConnectorID: "c1",
		EventType:   "access.granted",
		Payload:     json.RawMessage(`{"event":{"action":"access.granted"},"sourcetype":"socle:audit"}`),
		Provider:    "splunk",
		ConfigJSON:  string(cfg),
	})
	if err != nil {
		t.Fatalf("dispatch: %v", err)
	}
	if code != 200 {
		t.Fatalf("code=%d", code)
	}
	if gotAuth != "Splunk secret-token" {
		t.Fatalf("auth=%q", gotAuth)
	}
	if !strings.Contains(gotBody, "access.granted") {
		t.Fatalf("body=%s", gotBody)
	}
}

func TestDispatcher_MissingEndpoint(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := NewDispatcher(logger)
	_, err := d.Dispatch(context.Background(), Delivery{
		Provider:   "splunk",
		ConfigJSON: `{"hec_token":"x"}`,
	})
	if err == nil || !strings.Contains(err.Error(), "endpoint") {
		t.Fatalf("expected endpoint error, got %v", err)
	}
}

func TestConsumer_SuccessMarksDelivered(t *testing.T) {
	store := newMemStore(Delivery{
		ID: "d1", ConnectorID: "c1", EventType: "access.granted",
		Payload: json.RawMessage(`{}`), Provider: "splunk",
	})
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	c := NewConsumer(store, &stubDispatcher{code: 200}, logger, time.Hour, 10)
	c.inTickRetries = 1
	c.tick(context.Background())

	if store.delivered["d1"] != 200 {
		t.Fatalf("expected delivered, got %v", store.delivered)
	}
}

func TestConsumer_FailureRetriesThenMarksRetrying(t *testing.T) {
	store := newMemStore(Delivery{
		ID: "d1", ConnectorID: "c1", Attempts: 0,
		Payload: json.RawMessage(`{}`), Provider: "splunk",
	})
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	stub := &stubDispatcher{code: 503, err: errors.New("unavailable")}
	c := NewConsumer(store, stub, logger, time.Hour, 10)
	c.maxAttempts = 5
	c.inTickRetries = 2
	c.initialBackoff = time.Millisecond
	c.tick(context.Background())

	if store.retrying["d1"] != 1 {
		t.Fatalf("expected retrying attempt_count=1, got %v", store.retrying)
	}
	if _, ok := store.failed["d1"]; ok {
		t.Fatal("should not be failed yet")
	}
	if stub.calls != 2 {
		t.Fatalf("expected 2 in-tick retries, got %d", stub.calls)
	}
}

func TestConsumer_ExhaustedAttemptsMarksFailedAndConnectorError(t *testing.T) {
	store := newMemStore(Delivery{
		ID: "d1", ConnectorID: "c1", Attempts: 4,
		Payload: json.RawMessage(`{}`), Provider: "splunk",
	})
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	c := NewConsumer(store, &stubDispatcher{code: 500, err: errors.New("boom")}, logger, time.Hour, 10)
	c.maxAttempts = 5
	c.inTickRetries = 1
	c.tick(context.Background())

	if store.failed["d1"] != 5 {
		t.Fatalf("expected failed with attempts=5, got %v", store.failed)
	}
	if !store.connectorErr["c1"] {
		t.Fatal("expected connector marked error")
	}
}
