package com.spirit.koil.api.kpak.security;

import java.security.PrivateKey;
import java.security.PublicKey;

public record KPakKeyPair(
    PrivateKey privateKey,
    PublicKey publicKey
) {

}
