package com.spirit.koil.api.design;

public interface KoilListBoundsAccess {
    int koil$getListTop();

    int koil$getListBottom();

    default void koil$setListBounds(int top, int bottom) {
    }
}
