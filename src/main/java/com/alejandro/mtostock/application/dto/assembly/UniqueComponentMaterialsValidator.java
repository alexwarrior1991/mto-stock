package com.alejandro.mtostock.application.dto.assembly;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Comprueba {@link UniqueComponentMaterials}. Una lista ausente, una línea ausente o una línea sin
 * material no cuentan como repetidas: las rechazan sus propias restricciones.
 */
public class UniqueComponentMaterialsValidator implements ConstraintValidator<UniqueComponentMaterials, List<AssemblyComponentRequest>> {

    @Override
    public boolean isValid(List<AssemblyComponentRequest> components, ConstraintValidatorContext context) {
        if (components == null) {
            return true;
        }
        Set<UUID> seen = new HashSet<>();
        return components.stream()
                .filter(Objects::nonNull)
                .map(AssemblyComponentRequest::materialId)
                .filter(Objects::nonNull)
                .allMatch(seen::add);
    }
}
