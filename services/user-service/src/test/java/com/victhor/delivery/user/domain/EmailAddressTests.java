package com.victhor.delivery.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTests {

    @Test
    void stripsWhitespaceAndUsesCaseInsensitiveIdentity() {
        assertThat(new EmailAddress("  Customer+Orders@Example.COM  "))
                .isEqualTo(new EmailAddress("customer+orders@example.com"));
    }

    @Test
    void normalizationDoesNotDependOnTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(new EmailAddress("IDENTITY@EXAMPLE.COM").value()).isEqualTo("identity@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "customer@example.com", "customer.name@example.com", "a+b@example.co.uk",
            "a_b@example.com", "a-b@my-domain.example", "a@b.c", "a!#$%&'*+/=?^_`{|}~-@example.com" })
    void acceptsTheSupportedAsciiSyntax(String value) {
        assertThat(new EmailAddress(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\n", "\u2003", "customer", "customer@", "@example.com",
            "customer@@example.com", "customer@example", "customer@example..com", "customer@.example.com",
            "customer@example.com.", ".customer@example.com", "customer.@example.com", "customer..name@example.com",
            "customer name@example.com", "customer@exam ple.com", "customer@-example.com", "customer@example-.com",
            "customer@example_com", "customer@ex_ample.com", "\"customer\"@example.com", "customer@[127.0.0.1]",
            "joão@example.com", "customer@exämple.com", "Kustomer@example.com", "customer\n@example.com" })
    void rejectsMissingOrUnsupportedAddresses(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new EmailAddress(value));
    }

    @Test
    void acceptsALocalPartAtTheLimitAndRejectsOneAboveIt() {
        assertThat(new EmailAddress("a".repeat(64) + "@example.com").value()).startsWith("a".repeat(64));
        assertThatIllegalArgumentException().isThrownBy(() -> new EmailAddress("a".repeat(65) + "@example.com"));
    }

    @Test
    void acceptsDomainLabelsAtTheLimitAndRejectsOneAboveIt() {
        assertThat(new EmailAddress("customer@" + "a".repeat(63) + ".com").value()).contains("a".repeat(63));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailAddress("customer@" + "a".repeat(64) + ".com"));
    }

    @Test
    void acceptsTheMaximumTotalLengthAfterStrippingOuterWhitespace() {
        String value = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);

        assertThat(new EmailAddress("  " + value + "  ").value()).hasSize(254).isEqualTo(value);
        assertThatIllegalArgumentException().isThrownBy(() -> new EmailAddress(value + "d"));
    }
}
