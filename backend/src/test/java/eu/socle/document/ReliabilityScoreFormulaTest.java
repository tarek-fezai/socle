// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Formule pure — indépendante des déclencheurs et de la persistance.
 */
class ReliabilityScoreFormulaTest {

    ReliabilityScoreService service;

    @BeforeEach
    void setUp() {
        // JdbcTemplate / properties / clock / events non utilisés par compute()
        service = new ReliabilityScoreService(null, new ReliabilityScoreProperties(), null, null);
    }

    @Test
    void recentlyValidated_noComments_noMandatoryAck_near100() {
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        var result = service.compute(new ReliabilityScoreService.ScoreInputs(
                now.minus(2, ChronoUnit.DAYS),
                365,
                0,
                0,
                false,
                false,
                0,
                0,
                null,
                now
        ));

        assertThat(result.freshness()).isCloseTo(100.0 * (1.0 - 2.0 / 365.0), within(0.01));
        assertThat(result.resolution()).isEqualTo(100.0);
        assertThat(result.attestation()).isEqualTo(100.0);
        assertThat(result.score()).isCloseTo(new BigDecimal("99.78"), within(new BigDecimal("0.01")));
    }

    @Test
    void validatedLongAgo_beyondCycle_freshnessZero() {
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        var result = service.compute(new ReliabilityScoreService.ScoreInputs(
                now.minus(400, ChronoUnit.DAYS),
                365,
                0,
                0,
                false,
                false,
                0,
                0,
                null,
                now
        ));

        assertThat(result.freshness()).isEqualTo(0.0);
        // 0.4*0 + 0.3*100 + 0.3*100 = 60
        assertThat(result.score()).isEqualByComparingTo("60.00");
    }

    @Test
    void unresolvedComments_lowerResolutionProportionally() {
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        var result = service.compute(new ReliabilityScoreService.ScoreInputs(
                now,
                365,
                4,
                1,
                false,
                false,
                0,
                0,
                null,
                now
        ));

        assertThat(result.resolution()).isEqualTo(25.0);
        // 0.4*100 + 0.3*25 + 0.3*100 = 40 + 7.5 + 30 = 77.5
        assertThat(result.score()).isEqualByComparingTo("77.50");
    }

    @Test
    void mandatoryAck_overduePartial_appliesHalfPenalty_noDivByZero() {
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        LocalDate dueYesterday = LocalDate.of(2026, 9, 27);

        var overdue = service.compute(new ReliabilityScoreService.ScoreInputs(
                now,
                365,
                0,
                0,
                true,
                true,
                5,
                10,
                dueYesterday,
                now
        ));
        // base 50%, overdue → 25%
        assertThat(overdue.attestation()).isEqualTo(25.0);
        // 0.4*100 + 0.3*100 + 0.3*25 = 40 + 30 + 7.5 = 77.5
        assertThat(overdue.score()).isEqualByComparingTo("77.50");

        var zeroAudience = service.compute(new ReliabilityScoreService.ScoreInputs(
                now,
                365,
                0,
                0,
                true,
                true,
                0,
                0,
                dueYesterday,
                now
        ));
        assertThat(zeroAudience.attestation()).isEqualTo(100.0);
        assertThat(zeroAudience.score()).isEqualByComparingTo("100.00");
    }
}
