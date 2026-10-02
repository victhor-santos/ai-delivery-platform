package com.victhor.delivery.delivery.application;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.victhor.delivery.delivery.domain.Courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourierServiceTests {

    @Mock
    private CourierRepository couriers;

    @Test
    void createsActiveCouriersWithDistinctServerGeneratedIdentities() {
        when(couriers.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new CourierService(couriers);

        Courier first = service.create();
        Courier second = service.create();

        assertThat(first.active()).isTrue();
        assertThat(second.active()).isTrue();
        assertThat(first.id()).isNotEqualTo(second.id());
        verify(couriers).create(first);
        verify(couriers).create(second);
    }

    @Test
    void reportsMissingCouriers() {
        var service = new CourierService(couriers);
        assertThatThrownBy(() -> service.findById(UUID.randomUUID())).isInstanceOf(CourierNotFoundException.class);
    }
}
