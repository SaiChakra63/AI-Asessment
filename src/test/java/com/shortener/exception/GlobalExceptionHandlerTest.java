package com.shortener.exception;

import com.shortener.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/api/v1/urls/shorten");

    @Test
    void mapsUnsupportedRequestContentTypeTo415() {
        var response = handler.handleUnsupportedMediaType(
                mock(HttpMediaTypeNotSupportedException.class), request);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", body(response.getBody()).errorCode());
    }

    @Test
    void mapsUnacceptableResponseTypeTo406() {
        var response = handler.handleUnacceptableMediaType(
                mock(HttpMediaTypeNotAcceptableException.class), request);

        assertEquals(HttpStatus.NOT_ACCEPTABLE, response.getStatusCode());
        assertEquals("NOT_ACCEPTABLE", body(response.getBody()).errorCode());
    }

    private ErrorResponse body(ErrorResponse response) {
        if (response == null) {
            throw new AssertionError("Expected an error response body");
        }
        return response;
    }
}
