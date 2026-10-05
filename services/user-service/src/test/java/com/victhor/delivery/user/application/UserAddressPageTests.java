package com.victhor.delivery.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.victhor.delivery.user.domain.UserAddress;

class UserAddressPageTests {

    @Test
    void copiesTheAddressListAndPreventsItsModification() {
        UserAddress address = UserAddress.create(UUID.randomUUID(), "Casa", "Rua A", 0, 0);
        var original = new ArrayList<>(List.of(address));

        var page = new UserAddressPage(original, 0, 20, 1);
        original.clear();

        assertThat(page.items()).containsExactly(address);
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @CsvSource({ "0,20,0", "1,20,1", "20,20,1", "21,20,2", "41,20,3",
            "9223372036854775807,100,92233720368547759" })
    void computesTotalPagesWithoutOverflow(long totalElements, int size, long totalPages) {
        assertThat(new UserAddressPage(List.of(), 0, size, totalElements).totalPages()).isEqualTo(totalPages);
    }
}
