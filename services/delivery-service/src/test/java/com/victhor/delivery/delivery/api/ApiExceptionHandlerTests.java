package com.victhor.delivery.delivery.api;

import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.victhor.delivery.delivery.application.DeliveryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeliveryController.class)
class ApiExceptionHandlerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeliveryService deliveries;

    @ParameterizedTest
    @MethodSource("failures")
    void mapsFailuresWithoutExposingInternalDetails(RuntimeException failure, int expectedStatus) throws Exception {
        UUID id = UUID.randomUUID();
        when(deliveries.complete(id)).thenThrow(failure);

        var response = mvc.perform(post("/api/deliveries/{id}/complete", id))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("internal detail", "23505", "23514", "SQLException");
        if (expectedStatus == 500) {
            assertThat(response.getContentAsString()).contains("Não foi possível processar a requisição.");
        }
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new IllegalStateException("internal detail"), 500),
                Arguments.of(new OptimisticLockingFailureException("internal detail"), 409),
                Arguments.of(new DataIntegrityViolationException("internal detail",
                        new SQLException("internal detail", "23505")), 409),
                Arguments.of(new DataIntegrityViolationException("internal detail",
                        new SQLException("internal detail", "23514")), 500),
                Arguments.of(new InvalidDataAccessApiUsageException("internal detail"), 500),
                Arguments.of(new InvalidDataAccessApiUsageException("internal detail",
                        new IllegalArgumentException("internal detail")), 400));
    }
}
