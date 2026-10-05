// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.branding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContrastCheckerTest {

    @Test
    void whiteOnWhiteIsRatioOne() {
        assertThat(ContrastChecker.contrastOnWhite("#FFFFFF")).isEqualTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(ContrastChecker.passesAaOnWhite("#FFFFFF")).isFalse();
    }

    @Test
    void blackOnWhiteIsRatio21() {
        assertThat(ContrastChecker.contrastOnWhite("#000000")).isEqualTo(21.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(ContrastChecker.passesAaOnWhite("#000000")).isTrue();
    }

    @Test
    void aaThresholdBoundary() {
        // #767676 est le gris le plus clair qui passe AA (4.54) ; #777777 échoue (4.48).
        assertThat(ContrastChecker.passesAaOnWhite("#767676")).isTrue();
        assertThat(ContrastChecker.passesAaOnWhite("#777777")).isFalse();
    }

    @Test
    void lightAccentsFail_darkAccentsPass() {
        assertThat(ContrastChecker.passesAaOnWhite("#FFEB3B")).isFalse(); // jaune
        assertThat(ContrastChecker.passesAaOnWhite("#1D4ED8")).isTrue();  // bleu soutenu
    }

    @Test
    void hexValidation() {
        assertThat(ContrastChecker.isValidHex("#1D4ED8")).isTrue();
        assertThat(ContrastChecker.isValidHex("#1d4ed8")).isTrue();
        assertThat(ContrastChecker.isValidHex("1D4ED8")).isFalse();
        assertThat(ContrastChecker.isValidHex("#FFF")).isFalse();
        assertThat(ContrastChecker.isValidHex("red")).isFalse();
        assertThat(ContrastChecker.isValidHex("#GGGGGG")).isFalse();
        assertThat(ContrastChecker.isValidHex(null)).isFalse();
        assertThat(ContrastChecker.normalizeHex("#1d4ed8")).isEqualTo("#1D4ED8");
    }
}
