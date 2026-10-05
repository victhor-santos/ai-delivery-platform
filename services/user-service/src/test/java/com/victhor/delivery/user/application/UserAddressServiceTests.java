package com.victhor.delivery.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserAddress;
import com.victhor.delivery.user.domain.UserProfile;

@ExtendWith(MockitoExtension.class)
class UserAddressServiceTests {

    private static final UserProfile USER = UserProfile.create("Ana", new EmailAddress("ana@example.com"));

    @Mock
    private UserRepository users;

    @Mock
    private UserAddressRepository addresses;

    private UserAddressService service;

    @BeforeEach
    void setUp() {
        service = new UserAddressService(users, addresses);
    }

    @Test
    void savesAnAddressOnlyAfterCheckingThatItsUserExists() {
        when(users.findById(USER.id())).thenReturn(Optional.of(USER));
        when(addresses.save(any(UserAddress.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserAddress created = service.create(USER.id(), "  Casa  ", "  Rua A  ", -23.55, -46.63);

        assertThat(created.userId()).isEqualTo(USER.id());
        assertThat(created.label()).isEqualTo("Casa");
        assertThat(created.address()).isEqualTo("Rua A");
        assertThat(created.latitude()).isEqualTo(-23.55);
        assertThat(created.longitude()).isEqualTo(-46.63);
        var order = inOrder(users, addresses);
        order.verify(users).findById(USER.id());
        order.verify(addresses).save(created);
    }

    @Test
    void doesNotSaveAnAddressForAMissingUser() {
        when(users.findById(USER.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER.id(), "Casa", "Rua A", 0, 0))
                .isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(addresses);
    }

    @ParameterizedTest
    @MethodSource("invalidDetails")
    void rejectsInvalidDetailsBeforeAnyLookupOrWrite(String label, String address,
            double latitude, double longitude) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.create(USER.id(), label, address, latitude, longitude));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.update(USER.id(), UUID.randomUUID(), label, address, latitude, longitude));

        verifyNoInteractions(users, addresses);
    }

    @Test
    void findsAnAddressUsingBothTheUserAndAddressIds() {
        UserAddress address = UserAddress.create(USER.id(), "Casa", "Rua A", 0, 0);
        when(addresses.findById(USER.id(), address.id())).thenReturn(Optional.of(address));

        assertThat(service.findById(USER.id(), address.id())).isEqualTo(address);
        verify(addresses).findById(USER.id(), address.id());
        verifyNoInteractions(users);
    }

    @Test
    void reportsAnAddressOutsideTheRequestedUserAsNotFound() {
        UserAddress otherAddress = UserAddress.create(UUID.randomUUID(), "Casa", "Rua A", 0, 0);
        when(addresses.findById(USER.id(), otherAddress.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(USER.id(), otherAddress.id()))
                .isInstanceOf(UserAddressNotFoundException.class);
        verifyNoInteractions(users);
    }

    @Test
    void updatesOnlyTheAddressWithinTheRequestedUser() {
        UserAddress original = UserAddress.create(USER.id(), "Casa", "Rua A", 0, 0);
        UserAddress replacement = original.replace("Trabalho", "Rua B", 45, -90);
        when(addresses.update(USER.id(), original.id(), "Trabalho", "Rua B", 45, -90))
                .thenReturn(Optional.of(replacement));

        assertThat(service.update(USER.id(), original.id(), "  Trabalho  ", "  Rua B  ", 45, -90))
                .isEqualTo(replacement);
        verify(addresses).update(USER.id(), original.id(), "Trabalho", "Rua B", 45, -90);
        verifyNoInteractions(users);
    }

    @Test
    void doesNotCreateAMissingAddressDuringAnUpdate() {
        UUID id = UUID.randomUUID();
        when(addresses.update(USER.id(), id, "Casa", "Rua A", 0, 0)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(USER.id(), id, "Casa", "Rua A", 0, 0))
                .isInstanceOf(UserAddressNotFoundException.class);
        verifyNoInteractions(users);
    }

    @Test
    void listsAddressesAfterCheckingThatTheUserExists() {
        var page = new UserAddressPage(List.of(UserAddress.create(USER.id(), "Casa", "Rua A", 0, 0)), 2, 20, 41);
        when(users.findById(USER.id())).thenReturn(Optional.of(USER));
        when(addresses.findAll(USER.id(), 2, 20)).thenReturn(page);

        assertThat(service.findAll(USER.id(), 2, 20)).isEqualTo(page);
        var order = inOrder(users, addresses);
        order.verify(users).findById(USER.id());
        order.verify(addresses).findAll(USER.id(), 2, 20);
    }

    @Test
    void distinguishesAMissingUserFromAnEmptyAddressList() {
        when(users.findById(USER.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findAll(USER.id(), 0, 20)).isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(addresses);
    }

    @Test
    void acceptsTheMaximumSizeAndALargeValidOffset() {
        when(users.findById(USER.id())).thenReturn(Optional.of(USER));
        var maximumSizePage = new UserAddressPage(List.of(), 0, 100, 0);
        var maximumOffsetPage = new UserAddressPage(List.of(), Integer.MAX_VALUE, 1, 0);
        when(addresses.findAll(USER.id(), 0, 100)).thenReturn(maximumSizePage);
        when(addresses.findAll(USER.id(), Integer.MAX_VALUE, 1)).thenReturn(maximumOffsetPage);

        assertThat(service.findAll(USER.id(), 0, 100)).isEqualTo(maximumSizePage);
        assertThat(service.findAll(USER.id(), Integer.MAX_VALUE, 1)).isEqualTo(maximumOffsetPage);
    }

    @ParameterizedTest
    @CsvSource({ "-1,20", "0,0", "0,-1", "0,101", "2147483647,2", "21474837,100" })
    void rejectsInvalidPaginationBeforeLookingUpTheUser(int page, int size) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.findAll(USER.id(), page, size));

        verifyNoInteractions(users, addresses);
    }

    static Stream<Arguments> invalidDetails() {
        return Stream.of(Arguments.of(null, "Rua A", 0, 0), Arguments.of(" ", "Rua A", 0, 0),
                Arguments.of("a".repeat(81), "Rua A", 0, 0), Arguments.of("Casa", null, 0, 0),
                Arguments.of("Casa", "\u2003", 0, 0), Arguments.of("Casa", "a".repeat(256), 0, 0),
                Arguments.of("Casa", "Rua A", Double.NaN, 0), Arguments.of("Casa", "Rua A", 0, Double.POSITIVE_INFINITY),
                Arguments.of("Casa", "Rua A", 90.000001, 0), Arguments.of("Casa", "Rua A", 0, -180.000001));
    }
}
