package com.yourcompany.core.services.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourcompany.core.exceptions.OpenAIChatServiceException;
import com.yourcompany.core.services.OpenAIChatService;

/**
 * Default implementation of {@link OpenAIChatService} that communicates with the OpenAI Chat Completions API.
 */
@Component(service = OpenAIChatService.class, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = OpenAIChatServiceImpl.Configuration.class)
public class OpenAIChatServiceImpl implements OpenAIChatService {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAIChatServiceImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_MODEL = "gpt-3.5-turbo";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private volatile String apiKey;
    private volatile String apiUrl;
    private volatile String model;
    private volatile int connectionTimeout;
    private volatile int socketTimeout;

    @ObjectClassDefinition(name = "OpenAI Chat Service", description = "Configuration for the OpenAI chat integration")
    public @interface Configuration {

        @AttributeDefinition(name = "API Key", description = "OpenAI API key used to authorize requests.", type = AttributeType.PASSWORD)
        String apiKey();

        @AttributeDefinition(name = "API URL", description = "Endpoint URL for the chat completions API", defaultValue = DEFAULT_ENDPOINT)
        String apiUrl();

        @AttributeDefinition(name = "Model", description = "OpenAI model to use for chat completions", defaultValue = DEFAULT_MODEL)
        String model();

        @AttributeDefinition(name = "Connection timeout (ms)", description = "Maximum time in milliseconds to establish the connection", defaultValue = "10000")
        int connectionTimeout();

        @AttributeDefinition(name = "Socket timeout (ms)", description = "Maximum time in milliseconds to wait for data", defaultValue = "20000")
        int socketTimeout();
    }

    @Activate
    @Modified
    protected void activate(final Configuration configuration) {
        this.apiKey = trimToNull(configuration.apiKey());
        this.apiUrl = trimToNull(configuration.apiUrl());
        this.model = trimToNull(configuration.model());
        this.connectionTimeout = configuration.connectionTimeout();
        this.socketTimeout = configuration.socketTimeout();
        LOG.debug("OpenAIChatService configured with endpoint {} and model {}", this.apiUrl, this.model);
    }

    @Override
    public @NotNull String getChatResponse(@NotNull final String prompt) throws OpenAIChatServiceException {
        final String sanitizedPrompt = trimToNull(prompt);
        if (sanitizedPrompt == null) {
            throw new OpenAIChatServiceException("Prompt must not be empty.");
        }
        if (apiKey == null) {
            LOG.warn("OpenAI API key has not been configured.");
            throw new OpenAIChatServiceException("OpenAI API key is not configured.");
        }
        final String endpoint = apiUrl != null ? apiUrl : DEFAULT_ENDPOINT;

        final HttpPost post = new HttpPost(endpoint);
        post.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        post.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        post.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        post.setConfig(RequestConfig.custom()
                .setConnectTimeout(connectionTimeout)
                .setConnectionRequestTimeout(connectionTimeout)
                .setSocketTimeout(socketTimeout)
                .build());

        final ObjectNode payload = buildPayload(sanitizedPrompt);
        try {
            post.setEntity(new StringEntity(OBJECT_MAPPER.writeValueAsString(payload), ContentType.APPLICATION_JSON));
        } catch (JsonProcessingException e) {
            throw new OpenAIChatServiceException("Unable to serialise chat request payload.", e);
        }

        try (CloseableHttpClient client = HttpClients.createDefault();
                CloseableHttpResponse httpResponse = client.execute(post)) {

            final int statusCode = httpResponse.getStatusLine().getStatusCode();
            final String responseBody = httpResponse.getEntity() != null
                    ? EntityUtils.toString(httpResponse.getEntity(), StandardCharsets.UTF_8)
                    : "";

            if (statusCode < 200 || statusCode >= 300) {
                LOG.error("OpenAI API returned status {}.", statusCode);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("OpenAI API error response body: {}", responseBody);
                }
                throw new OpenAIChatServiceException(buildErrorMessage(statusCode, responseBody));
            }

            return extractMessageFromResponse(responseBody);
        } catch (IOException ex) {
            LOG.error("Error while invoking the OpenAI API", ex);
            throw new OpenAIChatServiceException("Unable to contact the OpenAI API.", ex);
        }
    }

    private ObjectNode buildPayload(final String prompt) {
        final ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("model", model != null ? model : DEFAULT_MODEL);
        final ArrayNode messages = root.putArray("messages");
        final ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return root;
    }

    private String extractMessageFromResponse(final String responseBody) throws OpenAIChatServiceException {
        try {
            final JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            final JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                final JsonNode firstChoice = choices.get(0);
                final JsonNode messageNode = firstChoice.path("message");
                final String content = trimToNull(messageNode.path("content").asText());
                if (content != null) {
                    return content;
                }
            }

            LOG.warn("OpenAI API response did not contain any message content.");
            throw new OpenAIChatServiceException("OpenAI API returned an empty response.");
        } catch (JsonProcessingException e) {
            LOG.error("Unable to parse OpenAI response payload.", e);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Raw response body: {}", responseBody);
            }
            throw new OpenAIChatServiceException("Unable to parse the OpenAI API response.", e);
        }
    }

    private String buildErrorMessage(final int statusCode, final String responseBody) {
        try {
            final JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            final JsonNode errorNode = root.path("error");
            final String errorMessage = trimToNull(errorNode.path("message").asText());
            if (errorMessage != null) {
                return String.format("OpenAI API error (%d): %s", statusCode, errorMessage);
            }
        } catch (JsonProcessingException e) {
            LOG.debug("Unable to parse error payload from OpenAI response.", e);
        }
        return String.format("OpenAI API error (%d).", statusCode);
    }

    @VisibleForTesting
    String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
