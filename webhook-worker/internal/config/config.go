// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	HTTPAddr     string
	DatabaseURL  string
	PollInterval time.Duration
	BatchSize    int

	SplunkHECURL   string
	SplunkHECToken string
	DatadogURL     string
	DatadogAPIKey  string
	SentinelURL    string
	SentinelKey    string
}

func Load() Config {
	return Config{
		HTTPAddr:       getenv("HTTP_ADDR", ":8090"),
		DatabaseURL:    getenv("DATABASE_URL", "postgres://socle:socle@localhost:5433/socle_core?sslmode=disable"),
		PollInterval:   durationEnv("POLL_INTERVAL", 3*time.Second),
		BatchSize:      intEnv("BATCH_SIZE", 50),
		SplunkHECURL:   os.Getenv("SPLUNK_HEC_URL"),
		SplunkHECToken: os.Getenv("SPLUNK_HEC_TOKEN"),
		DatadogURL:     getenv("DATADOG_LOG_URL", "https://http-intake.logs.datadoghq.com/api/v2/logs"),
		DatadogAPIKey:  os.Getenv("DATADOG_API_KEY"),
		SentinelURL:    os.Getenv("SENTINEL_DCS_URL"),
		SentinelKey:    os.Getenv("SENTINEL_SHARED_KEY"),
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func intEnv(key string, fallback int) int {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return fallback
	}
	return n
}

func durationEnv(key string, fallback time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		return fallback
	}
	return d
}
