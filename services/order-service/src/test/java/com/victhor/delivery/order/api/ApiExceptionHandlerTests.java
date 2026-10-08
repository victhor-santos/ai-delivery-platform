package com.victhor.delivery.order.api;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.victhor.delivery.order.application.OrderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(SecurityConfiguration.class)
class ApiExceptionHandlerTests {

    private static final UUID CUSTOMER = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrderService orders;

    @MockitoBean
    private JwtDecoder decoder;

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() throws Exception {
        UUID id = UUID.randomUUID();
        when(orders.cancel(id, CUSTOMER)).thenThrow(new IllegalStateException("internal database detail"));
        var response = mvc.perform(post("/api/orders/{id}/cancel", id).with(customer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Não foi possível processar a requisição."))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("internal database detail", "IllegalStateException");
    }

    @Test
    void returnsConflictForConcurrentUpdates() throws Exception {
        UUID id = UUID.randomUUID();
        when(orders.cancel(id, CUSTOMER)).thenThrow(new OptimisticLockingFailureException("internal version detail"));
        var response = mvc.perform(post("/api/orders/{id}/cancel", id).with(customer()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("internal version detail");
    }

    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(CUSTOMER.toString()));
    }
}
