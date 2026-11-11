package com.alejandro.mtostock.application.dto.common;

/**
 * How an optional text of a request is read: blank is the same as absent.
 *
 * <p>A form sends {@code ""} for a field left empty, and a column that allows {@code null} but not a
 * blank value would refuse it. The request records apply this in their compact constructor, so it
 * holds however the record is built (JSON or code) and before Bean Validation and the idempotency
 * fingerprint see the value.
 */
public final class BlankText {

    private BlankText() {
    }

    /**
     * @return {@code null} for {@code null} or a text with nothing but whitespace; the text itself,
     *         untouched, otherwise
     */
    public static String toNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
