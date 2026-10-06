package com.victhor.delivery.user.application;

import com.victhor.delivery.user.domain.EmailAddress;
import com.victhor.delivery.user.domain.PasswordPolicy;
import com.victhor.delivery.user.domain.UserProfile;

public class AuthenticationService {

    private static final String DUMMY_PASSWORD = "authentication-dummy-password";

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
        return accounts.register(profile, passwords.hash(password));
    }

    public AccessToken login(String email, String password) {
        var account = accounts.findByEmail(new EmailAddress(email));
        boolean validInput = PasswordPolicy.canMatch(password);
        boolean matches = passwords.matches(validInput ? password : DUMMY_PASSWORD,
                account.map(AuthAccount::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !validInput || !matches) {
            throw new InvalidCredentialsException();
        }
        return tokens.issue(account.orElseThrow().userId());
    }
}
