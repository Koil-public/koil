package com.spirit.koil.api.jmixin;

import com.google.gson.JsonElement;

public record Point(
    String at,
    Integer ordinal,
    JsonElement expected,
    String type,
    Target target
) {

}
