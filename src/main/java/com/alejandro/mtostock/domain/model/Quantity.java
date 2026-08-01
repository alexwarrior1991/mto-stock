package com.alejandro.mtostock.domain.model;

import java.math.BigDecimal;

public record Quantity(BigDecimal amount) implements Comparable<Quantity> {

    public Quantity {
        DomainValidations.requireNonNull(amount, "amount");
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("amount must be greater than or equal to zero");
        }
        amount = amount.stripTrailingZeros();
    }

    public static Quantity zero() {
        return new Quantity(BigDecimal.ZERO);
    }

    public static Quantity of(String amount) {
        return new Quantity(new BigDecimal(DomainValidations.requireText(amount, "amount")));
    }

    public static Quantity of(BigDecimal amount) {
        return new Quantity(amount);
    }

    public boolean isZero() {
        return BigDecimal.ZERO.compareTo(amount) == 0;
    }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public Quantity add(Quantity other) {
        DomainValidations.requireNonNull(other, "other quantity");
        return new Quantity(amount.add(other.amount));
    }

    public Quantity subtract(Quantity other) {
        DomainValidations.requireNonNull(other, "other quantity");
        return new Quantity(amount.subtract(other.amount));
    }

    public long wholeUnitsAvailableFor(Quantity requiredPerUnit) {
        DomainValidations.requireNonNull(requiredPerUnit, "required quantity per unit");
        if (!requiredPerUnit.isPositive()) {
            throw new IllegalArgumentException("required quantity per unit must be greater than zero");
        }
        return amount.divideToIntegralValue(requiredPerUnit.amount).longValueExact();
    }

    public boolean hasSameValueAs(Quantity other) {
        DomainValidations.requireNonNull(other, "other quantity");
        return compareTo(other) == 0;
    }

    @Override
    public int compareTo(Quantity other) {
        DomainValidations.requireNonNull(other, "other quantity");
        return amount.compareTo(other.amount);
    }

}