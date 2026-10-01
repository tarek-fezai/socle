// SPDX-License-Identifier: AGPL-3.0-or-later
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/socle-eu/socle-webhook-worker/internal/config"
	"github.com/socle-eu/socle-webhook-worker/internal/connectors"
	"github.com/socle-eu/socle-webhook-worker/internal/outbox"
	"github.com/socle-eu/socle-webhook-worker/internal/server"
	"github.com/socle-eu/socle-webhook-worker/internal/siem"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	cfg := config.Load()

	store, err := outbox.NewPostgresStore(cfg.DatabaseURL)
	if err != nil {
		logger.Error("failed to connect to postgres", "error", err)
		os.Exit(1)
	}
	defer store.Close()

	dispatcher := connectors.NewDispatcher(cfg, logger)
	consumer := outbox.NewConsumer(store, dispatcher, logger, cfg.PollInterval, cfg.BatchSize)

	siemStore := siem.NewPostgresStoreFromDB(store.DB())
	siemDispatcher := siem.NewDispatcher(logger)
	siemConsumer := siem.NewConsumer(siemStore, siemDispatcher, logger, cfg.PollInterval, cfg.BatchSize)

	mux := server.NewMux()
	httpServer := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		logger.Info("webhook-worker http listening", "addr", cfg.HTTPAddr)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("http server error", "error", err)
			stop()
		}
	}()

	go consumer.Run(ctx)
	go siemConsumer.Run(ctx)
	logger.Info("webhook-worker consumers started", "poll_interval", cfg.PollInterval.String())

	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = httpServer.Shutdown(shutdownCtx)
	logger.Info("webhook-worker stopped")
}
