// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Horloge de test avançable à la demande. */
final class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-10-02T08:00:00Z");

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
