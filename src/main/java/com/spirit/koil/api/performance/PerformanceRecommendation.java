package com.spirit.koil.api.performance;

import java.util.LinkedHashMap;
import java.util.Map;

public record PerformanceRecommendation(
        String id,
        String title,
        String reason,
        PerformanceBottleneck bottleneck,
        Severity severity,
        boolean safeAutoFix,
        String settingKey,
        String beforeValue,
        String afterValue
) {
    public enum Severity {
        SAFE(0xFF2DA700, "Safe"),
        OPTIONAL(0xFF0085A4, "Optional"),
        CAUTION(0xFFE3B735, "Caution"),
        RISKY(0xFFE06A21, "Risky"),
        MANUAL_REVIEW(0xFFA7003A, "Manual Review"),
        APPLIED(0xFF00A8D8, "Applied"),
        REJECTED(0xFF7B5D5D, "Rejected"),
        REVERTED(0xFF8D835D, "Reverted");

        private final int color;
        private final String label;

        Severity(int color, String label) {
            this.color = color;
            this.label = label;
        }

        public int color() {
            return color;
        }

        public String label() {
            return label;
        }
    }

    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("reason", reason);
        map.put("bottleneck", bottleneck.name());
        map.put("severity", severity.name());
        map.put("safeAutoFix", safeAutoFix);
        map.put("settingKey", settingKey);
        map.put("beforeValue", beforeValue);
        map.put("afterValue", afterValue);
        return map;
    }
}
