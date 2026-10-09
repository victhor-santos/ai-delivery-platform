package com.victhor.delivery.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.victhor.delivery.catalog.application.RestaurantService;
import com.victhor.delivery.catalog.infrastructure.auth.AccessTokenConfiguration;

@WebMvcTest(RestaurantController.class)
@Import({ SecurityConfiguration.class, AccessTokenConfiguration.class })
class ApiExceptionHandlerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RestaurantService restaurants;

    @Test
    void hidesInternalDetailsWhenARequestFailsUnexpectedly() throws Exception {
        UUID id = UUID.randomUUID();
        when(restaurants.findById(id)).thenThrow(new IllegalStateException("internal database detail"));

        var response = mvc.perform(get("/api/catalog/restaurants/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Não foi possível processar a requisição."))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("internal database detail", "IllegalStateException");
    }
}
