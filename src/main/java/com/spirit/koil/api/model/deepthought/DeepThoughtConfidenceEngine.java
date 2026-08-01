package com.spirit.koil.api.model.deepthought;

public final class DeepThoughtConfidenceEngine {
    private DeepThoughtConfidenceEngine() {}

    public static Result calculate(DeepThoughtSession session) {
        long required = session.claims.stream().filter(DeepThoughtSession.Claim::required).count();
        long accepted = session.claims.stream().filter(DeepThoughtSession.Claim::required)
                .filter(claim -> "supported".equals(claim.state()) || "independently_verified".equals(claim.state())).count();
        int coverage = required == 0 ? (session.evidence.isEmpty() ? 0 : 60) : (int) Math.round(100.0 * accepted / required);
        boolean contradiction = session.contradictions.stream().anyMatch(value -> !"resolved".equals(value.state()));
        boolean failedRequiredTest = session.tests.stream().anyMatch(value -> "failed".equals(value.state()));
        boolean deterministic = required > 0 && accepted == required && !contradiction && !failedRequiredTest
                && session.claims.stream().filter(DeepThoughtSession.Claim::required)
                .allMatch(claim -> "independently_verified".equals(claim.state()));
        String classification;
        if (deterministic) classification = "verified";
        else if (coverage >= 80 && !contradiction && !failedRequiredTest && independentEvidence(session)) classification = "high confidence";
        else if (coverage >= 60 && !failedRequiredTest) classification = "moderate confidence";
        else if (!session.evidence.isEmpty()) classification = "low confidence";
        else classification = "unresolved";
        return new Result(classification, coverage, contradiction, failedRequiredTest);
    }

    private static boolean independentEvidence(DeepThoughtSession session) {
        return session.evidence.stream().filter(DeepThoughtSession.Evidence::independent)
                .map(DeepThoughtSession.Evidence::sourceIdentifier).distinct().count() >= 2
                || session.evidence.stream().anyMatch(value -> value.independent() && value.reproducible());
    }

    public record Result(String classification, int coveragePercent, boolean unresolvedContradiction, boolean failedRequiredTest) {}
}
