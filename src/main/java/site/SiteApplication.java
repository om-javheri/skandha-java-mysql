package site;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** The site's own server (docs/39 in UniversalIDE): the site from site/, its forms' messages and page views. */
@SpringBootApplication
public class SiteApplication {
    public static void main(String[] args) {
        SpringApplication.run(SiteApplication.class, args);
    }

    /** Every answer: browsers take its type as given. */
    @Bean
    Filter noSniff() {
        return (request, response, chain) -> {
            ((HttpServletResponse) response).setHeader("X-Content-Type-Options", "nosniff");
            chain.doFilter(request, response);
        };
    }
}
