package com.ginkgo.foodmap.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import com.ginkgo.foodmap.restaurant.policy.RestaurantPickerPolicy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

/** 穷举小集合的等概率分支，不用不稳定的随机频数断言；大集合验证不会只取目录窗口。 */
class RestaurantPickerPolicyTest {
    @Test
    void everyPermutationHasTheSameNumberOfRandomBranches() {
        var outcomes = new HashMap<List<Integer>, Integer>();
        for (int first = 0; first < 3; first++) {
            for (int second = 0; second < 2; second++) {
                int[] draws = {first, second, 0};
                var random =
                        new Random() {
                            private int position;

                            @Override
                            public int nextInt(int bound) {
                                return draws[position++];
                            }
                        };
                var selected = RestaurantPickerPolicy.sample(List.of(1, 2, 3), 5, random);
                outcomes.merge(selected, 1, Integer::sum);
            }
        }
        assertThat(outcomes).hasSize(6);
        assertThat(outcomes.values()).containsOnly(1);
    }

    @Test
    void canPickTheLastIdBeyondTenThousandWithoutMutatingInput() {
        var input = new ArrayList<>(LongStream.rangeClosed(1, 10005).boxed().toList());
        var selected =
                RestaurantPickerPolicy.sample(
                        input,
                        1,
                        new Random() {
                            @Override
                            public int nextInt(int bound) {
                                return bound - 1;
                            }
                        });
        assertThat(selected).containsExactly(10005L);
        assertThat(input.getFirst()).isEqualTo(1L);
        assertThat(RestaurantPickerPolicy.sample(List.of(), 5, new Random())).isEmpty();
    }
}
