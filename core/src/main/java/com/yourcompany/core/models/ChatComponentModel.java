package com.yourcompany.core.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

/**
 * Sling model used by the chat component to expose authorable properties and metadata to the HTL script.
 */
@Model(adaptables = Resource.class, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ChatComponentModel {

    private static final String DEFAULT_PROMPT_LABEL = "Ask OpenAI";
    private static final String DEFAULT_PROMPT_PLACEHOLDER = "Type your question here";
    private static final String DEFAULT_BUTTON_TEXT = "Send";
    private static final String SERVLET_PATH = "/bin/chat/response";

    @SlingObject
    private Resource resource;

    @ValueMapValue
    private String promptLabel;

    @ValueMapValue
    private String promptPlaceholder;

    @ValueMapValue
    private String buttonText;

    public String getPromptLabel() {
        return getOrDefault(promptLabel, DEFAULT_PROMPT_LABEL);
    }

    public String getPromptPlaceholder() {
        return getOrDefault(promptPlaceholder, DEFAULT_PROMPT_PLACEHOLDER);
    }

    public String getButtonText() {
        return getOrDefault(buttonText, DEFAULT_BUTTON_TEXT);
    }

    public String getServletPath() {
        return SERVLET_PATH;
    }

    public String getComponentId() {
        return resource != null ? "chat-" + Math.abs(resource.getPath().hashCode()) : "chat-component";
    }

    private String getOrDefault(final String value, final String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? defaultValue : trimmed;
    }
}
