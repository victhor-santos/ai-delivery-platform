package com.victhor.delivery.user.application;

public interface PasswordHasher {

    String hash(String password);

    boolean matches(String password, String hash);
}
