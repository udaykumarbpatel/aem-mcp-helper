package com.yourcompany.core.exceptions;

/**
 * Exception thrown when communication with the OpenAI API fails or returns an error.
 */
public class OpenAIChatServiceException extends Exception {

    private static final long serialVersionUID = -7527740410834284422L;

    public OpenAIChatServiceException(String message) {
        super(message);
    }

    public OpenAIChatServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
