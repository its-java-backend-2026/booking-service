package it.its.cinema.bookingservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Il titolo della Swagger UI: dal G6 i servizi sono tre, e ognuno ha la sua
 * aperta in una scheda del browser.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI bookingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("booking-service")
                .version("1.0.0")
                .description("Prenotazioni. Coordina shows-service e pricing-service."));
    }
}
