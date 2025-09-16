package com.yourcompany.core.servlets;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourcompany.core.exceptions.OpenAIChatServiceException;
import com.yourcompany.core.services.OpenAIChatService;

import static org.apache.sling.api.servlets.ServletResolverConstants.SLING_SERVLET_METHODS;
import static org.apache.sling.api.servlets.ServletResolverConstants.SLING_SERVLET_PATHS;

/**
 * Servlet endpoint that proxies chat requests to the OpenAI API via {@link OpenAIChatService}.
 */
@Component(service = Servlet.class, property = {
        Constants.SERVICE_DESCRIPTION + "=Chat response servlet",
        SLING_SERVLET_PATHS + "=/bin/chat/response",
        SLING_SERVLET_METHODS + "=POST"
})
public class ChatServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = -7442487407648164283L;
    private static final Logger LOG = LoggerFactory.getLogger(ChatServlet.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Reference
    private transient OpenAIChatService chatService;

    @Override
    protected void doPost(@NotNull SlingHttpServletRequest request,
            @NotNull SlingHttpServletResponse response) throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        final String requestBody;
        try {
            requestBody = readRequestBody(request);
        } catch (IOException e) {
            LOG.warn("Failed to read chat request payload.", e);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeError(response, "Unable to read request payload.");
            return;
        }

        if (isBlank(requestBody)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeError(response, "Request body must not be empty.");
            return;
        }

        final JsonNode payload;
        try {
            payload = OBJECT_MAPPER.readTree(requestBody);
        } catch (JsonProcessingException e) {
            LOG.warn("Invalid JSON payload supplied to chat servlet.", e);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeError(response, "Request payload is not valid JSON.");
            return;
        }

        final String prompt = payload.path("prompt").asText(null);
        if (isBlank(prompt)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeError(response, "Prompt is required.");
            return;
        }

        try {
            final String chatResponse = chatService.getChatResponse(prompt.trim());
            final ObjectNode responseBody = OBJECT_MAPPER.createObjectNode();
            responseBody.put("response", chatResponse);
            response.getWriter().write(responseBody.toString());
        } catch (OpenAIChatServiceException e) {
            LOG.error("Unable to retrieve response from OpenAI API.", e);
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            writeError(response, e.getMessage());
        } catch (Exception e) {
            LOG.error("Unexpected error while processing chat response.", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writeError(response, "An unexpected error occurred.");
        }
    }

    private String readRequestBody(final SlingHttpServletRequest request) throws IOException {
        final StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private void writeError(final SlingHttpServletResponse response, final String message) throws IOException {
        final ObjectNode error = OBJECT_MAPPER.createObjectNode();
        error.put("error", message);
        response.getWriter().write(error.toString());
    }

    private boolean isBlank(final String value) {
        return value == null || value.trim().isEmpty();
    }
}
