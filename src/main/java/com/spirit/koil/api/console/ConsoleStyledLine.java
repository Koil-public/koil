package com.spirit.koil.api.console;

import java.util.List;

public record ConsoleStyledLine(ConsoleRecord record, List<ConsoleStyledSpan> spans, String plainText) {
}
