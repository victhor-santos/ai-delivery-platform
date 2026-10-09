package com.victhor.delivery.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.victhor.delivery.user.domain.Role;
import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

class AuthenticationServiceTests {

    private final AuthAccountRepository accounts = mock(AuthAccountRepository.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final AccessTokenIssuer tokens = mock(AccessTokenIssuer.class);
    private final LoginThrottle throttle = mock(LoginThrottle.class);
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        when(passwords.hash(anyString())).thenReturn("dummy-hash");
        service = new AuthenticationService(accounts, passwords, tokens, throttle);
        clearInvocations(passwords);
    }

    @Test
    void validatesAndHashesBeforeAtomicRegistrationWithoutMintingAToken() {
        when(passwords.hash("new-password-123")).thenReturn("encoded-password");
        when(accounts.register(any(), anyString(), any())).thenAnswer(call -> call.getArgument(0));
        UserProfile profile = service.register("  Cliente  ", " USER@EXAMPLE.TEST ", "new-password-123");
        assertThat(profile.name()).isEqualTo("Cliente");
        assertThat(profile.email().value()).isEqualTo("user@example.test");
        verify(accounts).register(profile, "encoded-password", Role.CUSTOMER);
        verifyNoInteractions(tokens);
    }

    @Test
    void doesNotHashOrSaveInvalidRegistration() {
        assertThatThrownBy(() -> service.register("Cliente", "user@example.test", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accounts, passwords, tokens);
    }

    @Test
    void issuesAnAccessTokenOnlyAfterVerifyingTheStoredPassword() {
        UUID id = UUID.randomUUID();
        when(accounts.findByEmail(new EmailAddress("user@example.test")))
                .thenReturn(Optional.of(new AuthAccount(id, "stored-hash", Role.OPERATOR)));
        when(passwords.matches("correct-password", "stored-hash")).thenReturn(true);
        AccessToken token = new AccessToken("private-token", Instant.EPOCH.plusSeconds(900), 900);
        when(tokens.issue(id, Role.OPERATOR)).thenReturn(token);
        assertThat(service.login(" USER@EXAMPLE.TEST ", "correct-password")).isSameAs(token);
        assertThat(token.toString()).doesNotContain("private-token");
        assertThat(new AuthAccount(id, "stored-hash", Role.OPERATOR).toString()).doesNotContain("stored-hash");
    }

    @Test
    void unknownEmailStillPerformsPasswordVerificationAndCannotReceiveAToken() {
        when(accounts.findByEmail(any())).thenReturn(Optional.empty());
        when(passwords.matches("correct-password", "dummy-hash")).thenReturn(true);
        assertThatThrownBy(() -> service.login("absent@example.test", "correct-password"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwords).matches("correct-password", "dummy-hash");
        verifyNoInteractions(tokens);
    }

    @Test
    void wrongPasswordDoesNotMintAToken() {
        when(accounts.findByEmail(any())).thenReturn(Optional.of(new AuthAccount(UUID.randomUUID(), "stored-hash", Role.CUSTOMER)));
        assertThatThrownBy(() -> service.login("user@example.test", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwords).matches("wrong-password", "stored-hash");
        verifyNoInteractions(tokens);
    }

    @Test
    void oversizedUnicodeLoginUsesBoundedWorkAndCannotAuthenticateEvenIfTheFallbackMatches() {
        when(accounts.findByEmail(any())).thenReturn(Optional.of(new AuthAccount(UUID.randomUUID(), "stored-hash", Role.CUSTOMER)));
        when(passwords.matches(anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.login("user@example.test", "é".repeat(37)))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwords).matches("authentication-dummy-password", "stored-hash");
        verifyNoInteractions(tokens);
    }

    @Test
    void provisionsTheConfiguredOperatorOnlyOnce() {
        when(passwords.hash("operator-password-1")).thenReturn("operator-hash");
        when(accounts.findByEmail(new EmailAddress("ops@example.test"))).thenReturn(Optional.empty());
        service.ensureOperator(" OPS@example.test ", "operator-password-1");
        verify(accounts).register(argThat(profile -> profile.email().value().equals("ops@example.test")
                && profile.name().equals("Operador")), eq("operator-hash"), eq(Role.OPERATOR));

        UUID id = UUID.randomUUID();
        when(accounts.findByEmail(new EmailAddress("ops@example.test")))
                .thenReturn(Optional.of(new AuthAccount(id, "operator-hash", Role.OPERATOR)));
        when(passwords.matches("operator-password-1", "operator-hash")).thenReturn(true);
        clearInvocations(accounts);
        service.ensureOperator("ops@example.test", "operator-password-1");
        verify(accounts, never()).register(any(), anyString(), any());
        verify(accounts, never()).changePasswordHash(any(), anyString());
    }

    @Test
    void alignsTheOperatorPasswordWithTheConfiguration() {
        UUID id = UUID.randomUUID();
        when(accounts.findByEmail(any())).thenReturn(Optional.of(new AuthAccount(id, "old-hash", Role.OPERATOR)));
        when(passwords.hash("rotated-password-1")).thenReturn("new-hash");
        service.ensureOperator("ops@example.test", "rotated-password-1");
        verify(accounts).changePasswordHash(id, "new-hash");
    }

    @Test
    void neverPromotesACustomerWhoRegisteredTheOperatorEmailFirst() {
        when(accounts.findByEmail(any())).thenReturn(Optional.of(new AuthAccount(UUID.randomUUID(), "hash", Role.CUSTOMER)));
        assertThatThrownBy(() -> service.ensureOperator("ops@example.test", "operator-password-1"))
                .isInstanceOf(OperatorAccountConflictException.class);
        verify(accounts, never()).changePasswordHash(any(), anyString());
        verify(accounts, never()).register(any(), anyString(), any());
    }

    @Test
    void rejectsAWeakOperatorPasswordBeforeTouchingAccounts() {
        assertThatThrownBy(() -> service.ensureOperator("ops@example.test", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accounts);
    }

    @Test
    void countsEveryAttemptBeforeHashingAndClearsTheCountOnlyOnSuccess() {
        UUID id = UUID.randomUUID();
        when(accounts.findByEmail(any())).thenReturn(Optional.of(new AuthAccount(id, "stored-hash", Role.CUSTOMER)));
        when(passwords.matches("correct-password", "stored-hash")).thenReturn(true);
        assertThatThrownBy(() -> service.login(" USER@example.test ", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(throttle, never()).succeeded(anyString());
        service.login("user@example.test", "correct-password");
        verify(throttle, times(2)).acquire("user@example.test");
        verify(throttle).succeeded("user@example.test");
    }

    @Test
    void aLockedAccountIsRefusedWithoutCheckingThePassword() {
        doThrow(new TooManyLoginAttemptsException(java.time.Duration.ofMinutes(3))).when(throttle).acquire(anyString());
        assertThatThrownBy(() -> service.login("user@example.test", "correct-password"))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        verifyNoInteractions(accounts, passwords, tokens);
    }
}
