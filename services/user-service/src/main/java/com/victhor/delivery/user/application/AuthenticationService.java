package com.victhor.delivery.user.application;

import java.util.UUID;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.PasswordPolicy;
import com.victhor.delivery.user.domain.Role;
import com.victhor.delivery.user.domain.UserProfile;

public class AuthenticationService {

    private static final String DUMMY_PASSWORD = "authentication-dummy-password";
    static final String OPERATOR_NAME = "Operador";

    private final AuthAccountRepository accounts;
    private final PasswordHasher passwords;
    private final AccessTokenIssuer tokens;
    private final String dummyHash;

    public AuthenticationService(AuthAccountRepository accounts, PasswordHasher passwords, AccessTokenIssuer tokens) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
        dummyHash = passwords.hash(DUMMY_PASSWORD);
    }

    public UserProfile register(String name, String email, String password) {
        var profile = UserProfile.create(name, new EmailAddress(email));
        PasswordPolicy.validateRegistration(password);
        return accounts.register(profile, passwords.hash(password), Role.CUSTOMER);
    }

    public AccessToken login(String email, String password) {
        var account = accounts.findByEmail(new EmailAddress(email));
        boolean validInput = PasswordPolicy.canMatch(password);
        boolean matches = passwords.matches(validInput ? password : DUMMY_PASSWORD,
                account.map(AuthAccount::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !validInput || !matches) {
            throw new InvalidCredentialsException();
        }
        return tokens.issue(account.orElseThrow().userId(), account.orElseThrow().role());
    }

    public Role roleOf(UUID userId) {
        return accounts.findByUserId(userId).map(AuthAccount::role).orElseThrow(UserNotFoundException::new);
    }

    /**
     * Creates the configured operator, or aligns its password with the configuration; never promotes a customer.
     */
    public void ensureOperator(String email, String password) {
        var address = new EmailAddress(email);
        PasswordPolicy.validateRegistration(password);
        var existing = accounts.findByEmail(address);
        if (existing.isEmpty()) {
            accounts.register(UserProfile.create(OPERATOR_NAME, address), passwords.hash(password), Role.OPERATOR);
            return;
        }
        var account = existing.orElseThrow();
        if (account.role() != Role.OPERATOR) {
            throw new OperatorAccountConflictException();
        }
        if (!passwords.matches(password, account.passwordHash())) {
            accounts.changePasswordHash(account.userId(), passwords.hash(password));
        }
    }
}
