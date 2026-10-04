package com.alejandro.mtostock.application.dto.assembly;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Una lista de materiales no repite material: en un conjunto, cada material es una sola línea con su
 * cantidad ({@code uq_assembly_component_assembly_material}). Sin esta comprobación la petición
 * llegaba a la base de datos y respondía 500; con ella es un 400 {@code REQ-VALIDATION} en el campo
 * {@code components}.
 *
 * <p>Solo se declara sobre el campo: en un record, un objetivo {@code METHOD} la pondría también en
 * el accesor, y en {@code TYPE_USE}, en el tipo de la lista.</p>
 */
@Documented
@Constraint(validatedBy = UniqueComponentMaterialsValidator.class)
@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueComponentMaterials {

    String message() default "each material can appear only once in components";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
