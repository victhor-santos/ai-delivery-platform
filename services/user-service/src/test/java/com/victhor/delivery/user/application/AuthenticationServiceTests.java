package com.victhor.delivery.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.UserProfile;

class AuthenticationServiceTests {

    private final AuthAccountRepository accounts = mock(AuthAccountRepository.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final AccessTokenIssuer tokens = mock(AccessTokenIssuer.class);
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        when(passwords.hash(anyString())).thenReturn("dummy-hash");
        service = new AuthenticationService(accounts, passwords, tokens);
        clearInvocations(passwords);
    }

    @Test
    void validatesAndHashesBeforeAtomicRegistrationWithoutMintingAToken() {
        when(passwords.hash("new-password-123")).thenReturn("encoded-password");
        when(accounts.register(any(), anyString())).thenAnswer(call -> call.getArgument(0));
        UserProfile profile = service.register("  Cliente  ", " USER@EXAMPLE.TEST ", "new-password-123");
        assertThat(profile.name()).isEqualTo("Cliente");
        assertThat(profile.email().value()).isEqualTo("user@example.test");
        verify(accounts).register(profile, "encoded-password");
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
                .thenReturn(Optional.of(new AuthAccount(id, "stored-hash")));
        when(passwords.matches("correct-password", "stored-hash")).thenReturn(true);
        AccessToken token = new AccessToken("private-token", Instant.EPOCH.plusSeconds(900), 900);
        when(tokens.issue(id)).thenReturn(token);
        assertThat(service.login(" USER@EXAMPLE.TEST ", "correct-password")).isSameAs(token);
        assertThat(token.toString()).doesNotContain("private-token");
        assertThat(new AuthAccount(id, "stored-hash").toString()).doesNotContain("stored-hash");
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
        when(accounts.findByEmail(any())).thenReturn(Optional.of(new AuthAccount(UUID.randomUUID(), "stored-hash")));
        assertThatThrownBy(() -> service.login("user@example.test", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwords).matches("wrong-password", "stored-hash");
        verifyNoInteractions(tokens);
    }

    @Test
    void oversizedUnicodeLoginUsesBoundedWorkAndCannotAuthenticateEvenIfTheFallbackMatches() {
        when(accounts.findByEmail(any())).thenReturn(Optional.of(new AuthAccount(UUID.randomUUID(), "stored-hash")));
        when(passwords.matches(anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.login("user@example.test", "é".repeat(37)))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(passwords).matches("authentication-dummy-password", "stored-hash");
        verifyNoInteractions(tokens);
    }
}
