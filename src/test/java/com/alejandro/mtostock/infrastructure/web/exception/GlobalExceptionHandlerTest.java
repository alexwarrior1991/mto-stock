package com.alejandro.mtostock.infrastructure.web.exception;

import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.exception.DuplicateCodeException;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.exception.ReservationException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundBusinessExceptionReturnsStableErrorResponse() {
        MockHttpServletRequest request = request("GET", "/api/v1/inventory/materials/" + UUID.randomUUID());
        request.addHeader("X-Correlation-Id", "corr-1");
        NotFoundException exception = new NotFoundException("Material", UUID.randomUUID());

        ResponseEntity<ApiErrorResponse> response = handler.handleNotFound(exception, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(404, body.status());
        assertEquals("NOT_FOUND", body.error());
        assertEquals(exception.getMessage(), body.message());
        assertEquals("MAT-404", body.errorCode());
        assertEquals("corr-1", body.correlationId());
        assertEquals(request.getRequestURI(), body.path());
        assertEquals("GET", body.method());
        assertTrue(body.validationErrors().isEmpty());
        assertNotNull(body.timestamp());
    }

    @Test
    void businessExceptionsUseSpecificStatusesAndCodes() {
        MockHttpServletRequest request = request("POST", "/api/v1/inventory/materials");

        ResponseEntity<ApiErrorResponse> duplicateResponse = handler.handleDuplicateCode(new DuplicateCodeException("Material", "MAT-001"), request);
        ResponseEntity<ApiErrorResponse> reservationResponse = handler.handleReservation(new ReservationException("Reservation expired."), request);

        assertEquals(HttpStatus.CONFLICT, duplicateResponse.getStatusCode());
        assertNotNull(duplicateResponse.getBody());
        assertEquals("MAT-409", duplicateResponse.getBody().errorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, reservationResponse.getStatusCode());
        assertNotNull(reservationResponse.getBody());
        assertEquals("RES-001", reservationResponse.getBody().errorCode());
    }

    @Test
    void aggregateNamesUsedByServicesKeepTheirSpecificNotFoundErrorCodes() {
        MockHttpServletRequest request = request("GET", "/api/v1/inventory/movements/" + UUID.randomUUID());

        assertEquals("STK-404", errorCodeOfNotFound("Stock movement", request));
        assertEquals("WH-404", errorCodeOfNotFound("Source warehouse", request));
        assertEquals("WH-404", errorCodeOfNotFound("Target warehouse", request));
        assertEquals("APP-404", errorCodeOfNotFound("Unknown aggregate", request));
    }

    @Test
    void methodArgumentValidationReturnsEveryValidationError() throws NoSuchMethodException {
        MockHttpServletRequest request = request("POST", "/api/v1/inventory/materials");
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("validatedEndpoint", SampleRequest.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        SampleRequest target = new SampleRequest("", "x");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "sampleRequest");
        bindingResult.addError(new FieldError("sampleRequest", "code", "must not be blank"));
        bindingResult.addError(new FieldError("sampleRequest", "description", "size must be between 2 and 40"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiErrorResponse> response = handler.handleMethodArgumentNotValid(exception, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("REQ-VALIDATION", body.errorCode());
        assertEquals(2, body.validationErrors().size());
        assertTrue(body.validationErrors().stream().anyMatch(error -> error.field().equals("code") && error.message().equals("must not be blank")));
        assertTrue(body.validationErrors().stream().anyMatch(error -> error.field().equals("description") && error.message().equals("size must be between 2 and 40")));
    }

    @Test
    void constraintViolationReturnsEveryViolation() {
        MockHttpServletRequest request = request("GET", "/api/v1/inventory/materials");
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ConstraintViolationException exception = new ConstraintViolationException(validator.validate(new SampleRequest("", "x")));

        ResponseEntity<ApiErrorResponse> response = handler.handleConstraintViolation(exception, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("REQ-VALIDATION", body.errorCode());
        assertEquals(2, body.validationErrors().size());
        assertTrue(body.validationErrors().stream().anyMatch(error -> error.field().endsWith("code")));
        assertTrue(body.validationErrors().stream().anyMatch(error -> error.field().endsWith("description")));
    }

    @Test
    void unexpectedExceptionReturnsGenericMessageWithoutLeakingDetails() {
        MockHttpServletRequest request = request("GET", "/api/v1/inventory/materials");
        RuntimeException exception = new RuntimeException("database password leaked detail");

        ResponseEntity<ApiErrorResponse> response = handler.handleException(exception, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("APP-500", body.errorCode());
        assertEquals("INTERNAL_SERVER_ERROR", body.error());
        assertNotEquals(exception.getMessage(), body.message());
        assertFalse(body.message().contains("database password"));
    }

    @SuppressWarnings("unused")
    private static void validatedEndpoint(SampleRequest request) {
    }

    private String errorCodeOfNotFound(String aggregate, MockHttpServletRequest request) {
        ApiErrorResponse body = handler.handleNotFound(new NotFoundException(aggregate, UUID.randomUUID()), request).getBody();
        assertNotNull(body);
        return body.errorCode();
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private record SampleRequest(
            @NotBlank String code,
            @Size(min = 2, max = 40) String description
    ) {
    }
}
