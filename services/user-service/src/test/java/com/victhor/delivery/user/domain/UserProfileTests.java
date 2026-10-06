package com.victhor.delivery.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserProfileTests {

    private static final EmailAddress EMAIL = new EmailAddress("customer@example.com");

    @Test
    void createsAProfileWithANewIdAndNormalizedName() {
        UserProfile created = UserProfile.create("  Ana Vitória  ", EMAIL);

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Ana Vitória");
        assertThat(created.email()).isEqualTo(EMAIL);
        assertThat(UserProfile.create("Ana Vitória", EMAIL).id()).isNotEqualTo(created.id());
    }

    @Test
    void changingTheNamePreservesIdentityAndEmailAndLeavesTheOriginalIntact() {
        UserProfile original = UserProfile.create("Ana", EMAIL);

        UserProfile renamed = original.updateName("  Ana Vitória  ");

        assertThat(renamed.id()).isEqualTo(original.id());
        assertThat(renamed.email()).isEqualTo(original.email());
        assertThat(renamed.name()).isEqualTo("Ana Vitória");
        assertThat(original.name()).isEqualTo("Ana");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003" })
    void rejectsMissingOrBlankNamesWhenCreatingAndRenaming(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> UserProfile.create(name, EMAIL));
        assertThatIllegalArgumentException().isThrownBy(() -> UserProfile.create("Ana", EMAIL).updateName(name));
    }

    @Test
    void enforcesTheNameLengthAfterStripping() {
        assertThat(UserProfile.create("  " + "a".repeat(120) + "  ", EMAIL).name()).hasSize(120);
        assertThatIllegalArgumentException().isThrownBy(() -> UserProfile.create("a".repeat(121), EMAIL));
        assertThatIllegalArgumentException().isThrownBy(() -> UserProfile.create("Ana", EMAIL).updateName("a".repeat(121)));
    }

    @Test
    void rejectsMissingIdentityOrEmailWhenRestoring() {
        assertThatNullPointerException().isThrownBy(() -> new UserProfile(null, "Ana", EMAIL));
        assertThatNullPointerException().isThrownBy(() -> new UserProfile(UUID.randomUUID(), "Ana", null));
    }
}
