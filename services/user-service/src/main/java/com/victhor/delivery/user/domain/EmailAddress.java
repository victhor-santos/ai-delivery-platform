package com.victhor.delivery.user.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Case-insensitive ASCII identity key; syntax validation does not verify mailbox ownership.
 */
public record EmailAddress(String value) {

    public static final int MAX_LENGTH = 254;
    public static final int MAX_LOCAL_PART_LENGTH = 64;

    private static final Pattern LOCAL_PART = Pattern.compile("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public EmailAddress {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Email address is required");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH || value.chars().anyMatch(character -> character > 127)) {
            throw new IllegalArgumentException("Email address must use ASCII and have at most 254 characters");
        }
        value = value.toLowerCase(Locale.ROOT);
        int separator = value.indexOf('@');
        if (separator < 1 || separator != value.lastIndexOf('@')) {
            throw new IllegalArgumentException("Email address format is invalid");
        }
        String localPart = value.substring(0, separator);
        String domain = value.substring(separator + 1);
        if (localPart.length() > MAX_LOCAL_PART_LENGTH || !LOCAL_PART.matcher(localPart).matches()
                || localPart.startsWith(".") || localPart.endsWith(".") || localPart.contains("..")) {
            throw new IllegalArgumentException("Email local part is invalid");
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException("Email domain must contain at least one dot");
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("Email domain label is invalid");
            }
        }
    }
}
