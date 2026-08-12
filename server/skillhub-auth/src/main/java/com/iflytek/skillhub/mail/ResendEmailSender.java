package com.iflytek.skillhub.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends transactional email via the Resend HTTPS API (api.resend.com:443).
 * Activated when {@code RESEND_API_KEY} is present in the environment;
 * otherwise the standard SMTP path is used.
 */
@Component
public class ResendEmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private static final String RESEND_API_URL = "https://api.resend.com/emails";
    private static final String API_KEY = System.getenv("RESEND_API_KEY");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ResendEmailSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isEnabled() {
        return API_KEY != null && !API_KEY.isEmpty();
    }

    public void send(String from, String to, String subject, String body) {
        if (!isEnabled()) {
            throw new IllegalStateException("RESEND_API_KEY not set");
        }
        try {
            Map<String, Object> payload = Map.of(
                    "from", from,
                    "to", List.of(to),
                    "subject", subject,
                    "text", body
            );
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RESEND_API_URL))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                log.info("Email sent via Resend to {}", to);
            } else {
                log.error("Resend returned {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Resend API returned " + response.statusCode());
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            log.error("Failed to send email via Resend to {}", to, ex);
            throw new RuntimeException("Resend send failed", ex);
        }
    }
}
