package com.spirit.koil.api.jmixin;

import java.util.List;

public record ClassDefinition(
    String id,
    String target,
    List<Operation> operations
) {

}
