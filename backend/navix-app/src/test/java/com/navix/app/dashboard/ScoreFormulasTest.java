package com.navix.app.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.app.dashboard.ScoreFormulas.Input;
import com.navix.app.dashboard.ScoreFormulas.Ranked;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreFormulasTest {

    @Test
    void creditWeightsAreThirtyTwentyFifty() {
        // processed 1.0, approval 0.5, efficiency 0.0 -> 10 * (0.3*1 + 0.2*0.5 + 0.5*0) = 4.0
        List<Input> in = ScoreFormulas.credit(10, 5, 5, 100, 0);
        assertThat(ScoreFormulas.score(in)).isEqualTo(4.0);
    }

    @Test
    void nullFactorsAreDroppedAndTheRestReweighted() {
        // nothing assigned (processed null), nothing decided (approval null): efficiency alone -> 10 * 0.8
        List<Input> in = ScoreFormulas.credit(0, 0, 0, 100, 80);
        assertThat(ScoreFormulas.score(in)).isEqualTo(8.0);
    }

    @Test
    void allNullGivesNullScoreAndNullStars() {
        List<Input> in = ScoreFormulas.credit(0, 0, 0, 0, 0);
        assertThat(ScoreFormulas.score(in)).isNull();
        assertThat(ScoreFormulas.stars(null)).isNull();
    }

    @Test
    void factorsAreClampedToZeroOne() {
        // 3.0 clamps to 1.0 and -2.0 to 0.0 -> 10 * (0.5 + 0) / 1.0 = 5.0
        List<Input> in = List.of(new Input("a", "A", 3.0, 0.5), new Input("b", "B", -2.0, 0.5));
        assertThat(ScoreFormulas.score(in)).isEqualTo(5.0);
    }

    @Test
    void scoreIsRoundedToTwoDecimalsAndStarsToWholeFive() {
        assertThat(ScoreFormulas.score(List.of(new Input("a", "A", 1.0 / 3, 1.0)))).isEqualTo(3.33);
        assertThat(ScoreFormulas.stars(7.1)).isEqualTo(4);
        assertThat(ScoreFormulas.stars(10.0)).isEqualTo(5);
        assertThat(ScoreFormulas.stars(0.4)).isEqualTo(0);
    }

    @Test
    void collectionAndTelecallerWeights() {
        // recovery 1.0 (w .5), closed 0.0 (w .3), volume 1.0 (w .2) -> 7.0
        assertThat(ScoreFormulas.score(ScoreFormulas.collection(100, 0, 10, 0, 10))).isEqualTo(7.0);
        // conversion 0.5 (.5), contact 1.0 (.3), volume 0.5 (.2) -> 10 * (.25 + .3 + .1) = 6.5
        assertThat(ScoreFormulas.score(ScoreFormulas.telecaller(10, 5, 10, 5, 10))).isEqualTo(6.5);
    }

    @Test
    void ratioIsNullWithoutADenominator() {
        assertThat(ScoreFormulas.ratio(5, 0)).isNull();
        assertThat(ScoreFormulas.ratio(0, 4)).isEqualTo(0.0);
    }

    @Test
    void factorsCarryDisplayAndStars() {
        var f = ScoreFormulas.factors(List.of(new Input("a", "A", 0.5, 0.3), new Input("b", "B", null, 0.2)));
        assertThat(f.get(0).display()).isEqualTo("50%");
        assertThat(f.get(0).stars()).isEqualTo(3);
        assertThat(f.get(1).value()).isNull();
        assertThat(f.get(1).stars()).isNull();
    }

    @Test
    void rankingOrdersByScoreThenVolumeThenNameWithNullScoreLast() {
        List<Ranked> ranked = ScoreFormulas.rank(List.of(
                new Ranked(1, "Zed", 5.0, 3),
                new Ranked(2, "Amy", 5.0, 3),
                new Ranked(3, "Bob", 5.0, 9),
                new Ranked(4, "Nil", null, 100),
                new Ranked(5, "Top", 9.0, 0)));
        assertThat(ranked).extracting(Ranked::staffId).containsExactly(5L, 3L, 2L, 1L, 4L);
    }
}
