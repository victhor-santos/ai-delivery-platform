package com.victhor.delivery.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTests {

    @Mock
    private UserRepository users;

    private UserProfileService service;

    @BeforeEach
    void setUp() {
        service = new UserProfileService(users);
    }

    @Test
    void savesAProfileWithNormalizedNameAndEmail() {
        when(users.save(any(UserProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfile created = service.create("  Ana Vitória  ", "  ANA@EXAMPLE.COM  ");

        assertThat(created.name()).isEqualTo("Ana Vitória");
        assertThat(created.email().value()).isEqualTo("ana@example.com");
        assertThat(created.id()).isNotNull();
        verify(users).save(created);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003" })
    void rejectsBlankNamesBeforeSavingOrUpdating(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(name, "ana@example.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.updateName(UUID.randomUUID(), name));

        verifyNoInteractions(users);
    }

    @Test
    void rejectsOverlongNamesBeforeSavingOrUpdating() {
        String name = "a".repeat(121);

        assertThatIllegalArgumentException().isThrownBy(() -> service.create(name, "ana@example.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.updateName(UUID.randomUUID(), name));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "ana", "ana@example", "ana..name@example.com", "aná@example.com" })
    void rejectsInvalidEmailBeforeSaving(String email) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.create("Ana", email));

        verifyNoInteractions(users);
    }

    @Test
    void propagatesADuplicateEmailReportedByPersistence() {
        when(users.save(any(UserProfile.class))).thenThrow(new EmailAlreadyRegisteredException());

        assertThatThrownBy(() -> service.create("Ana", "ana@example.com"))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void returnsAnExistingProfile() {
        UserProfile profile = UserProfile.create("Ana", new EmailAddress("ana@example.com"));
        when(users.findById(profile.id())).thenReturn(Optional.of(profile));

        assertThat(service.findById(profile.id())).isEqualTo(profile);
    }

    @Test
    void reportsAMissingProfile() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updatesTheNormalizedNameWithoutPassingAnEmailChange() {
        UserProfile original = UserProfile.create("Ana", new EmailAddress("ana@example.com"));
        UserProfile renamed = original.updateName("Ana Vitória");
        when(users.updateName(original.id(), "Ana Vitória")).thenReturn(Optional.of(renamed));

        assertThat(service.updateName(original.id(), "  Ana Vitória  ")).isEqualTo(renamed);
        verify(users).updateName(original.id(), "Ana Vitória");
    }

    @Test
    void reportsAMissingProfileWhenUpdatingItsName() {
        UUID id = UUID.randomUUID();
        when(users.updateName(id, "Ana")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateName(id, "Ana")).isInstanceOf(UserNotFoundException.class);
    }
}
