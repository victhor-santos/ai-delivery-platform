package com.victhor.delivery.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RestaurantTests {

	@Test
	void createsAnActiveRestaurantWithANewIdAndTrimmedName() {
		Restaurant restaurant = Restaurant.create("  Cantina Vitória  ");

		assertThat(restaurant.id()).isNotNull();
		assertThat(restaurant.name()).isEqualTo("Cantina Vitória");
		assertThat(restaurant.active()).isTrue();
		assertThat(Restaurant.create("Cantina Vitória").id()).isNotEqualTo(restaurant.id());
	}

	@Test
	void preservesIdentityAndActiveStateWhenRestoringARestaurant() {
		UUID id = UUID.randomUUID();

		Restaurant restaurant = new Restaurant(id, "Cantina", false);

		assertThat(restaurant.id()).isEqualTo(id);
		assertThat(restaurant.name()).isEqualTo("Cantina");
		assertThat(restaurant.active()).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t\n", "\u2003" })
	void rejectsMissingOrBlankNames(String name) {
		assertThatIllegalArgumentException().isThrownBy(() -> Restaurant.create(name));
		assertThatIllegalArgumentException().isThrownBy(() -> new Restaurant(UUID.randomUUID(), name, true));
	}

	@Test
	void rejectsNamesAboveTheMaximumLength() {
		assertThatIllegalArgumentException().isThrownBy(() -> Restaurant.create("a".repeat(121)));
	}

	@Test
	void acceptsNamesAtTheMaximumLength() {
		assertThat(Restaurant.create("a".repeat(120)).name()).hasSize(120);
	}

	@Test
	void rejectsMissingIdentity() {
		assertThatNullPointerException().isThrownBy(() -> new Restaurant(null, "Cantina", true));
	}
}
