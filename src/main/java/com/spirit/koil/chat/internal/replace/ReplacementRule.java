package com.spirit.koil.chat.internal.replace;

import java.util.ArrayList;
import java.util.List;

public class ReplacementRule {

    public final String trigger;
    public final String replacement;

    public ReplacementRule(String trigger, String replacement) {
        this.trigger = trigger;
        this.replacement = replacement;
    }


    public static final List<ReplacementRule> RULES = List.of(
        // i took all of these from https://github.com/artisticat1/obsidian-latex-suite
        new ReplacementRule("mk", "$|$"),
        new ReplacementRule("dm", "$$|$$"),

        new ReplacementRule("sr", "^{|}"),
        new ReplacementRule("cb", "^{3}"),
        new ReplacementRule("rd", "^{|}"),
        new ReplacementRule("_", "_{|}"),

        new ReplacementRule("sq", "\\sqrt{|}"),

        new ReplacementRule("//", "\\frac{|}{|}"),

        new ReplacementRule("\"", "\\text{|}"),
        new ReplacementRule("text", "\\text{|}"),

        new ReplacementRule("x1", "x_{1}"),

        new ReplacementRule("x,.", "\\mathbf{x}"),
        new ReplacementRule("x.,", "\\mathbf{x}"),

        new ReplacementRule("xdot", "\\dot{x}"),
        new ReplacementRule("xhat", "\\hat{x}"),
        new ReplacementRule("xbar", "\\bar{x}"),
        new ReplacementRule("xvec", "\\vec{x}"),
        new ReplacementRule("xtilde", "\\tilde{x}"),
        new ReplacementRule("xund", "\\underline{x}"),

        new ReplacementRule("ee", "e^{|}"),
        new ReplacementRule("invs", "^{-1}"),

        new ReplacementRule("@a", "\\alpha"),
        new ReplacementRule("@b", "\\beta"),
        new ReplacementRule("@g", "\\gamma"),
        new ReplacementRule("@G", "\\Gamma"),

        new ReplacementRule("@d", "\\delta"),
        new ReplacementRule("@D", "\\Delta"),

        new ReplacementRule("@e", "\\epsilon"),
        new ReplacementRule(":e", "\\varepsilon"),

        new ReplacementRule("@z", "\\zeta"),

        new ReplacementRule("@t", "\\theta"),
        new ReplacementRule("@T", "\\Theta"),

        new ReplacementRule("@k", "\\kappa"),

        new ReplacementRule("@l", "\\lambda"),
        new ReplacementRule("@L", "\\Lambda"),

        new ReplacementRule("@s", "\\sigma"),
        new ReplacementRule("@S", "\\Sigma"),

        new ReplacementRule("@o", "\\omega"),

        new ReplacementRule("eta", "\\eta"),
        new ReplacementRule("mu", "\\mu"),
        new ReplacementRule("nu", "\\nu"),

        new ReplacementRule("xi", "\\xi"),
        new ReplacementRule("Xi", "\\Xi"),

        new ReplacementRule("pi", "\\pi"),
        new ReplacementRule("Pi", "\\Pi"),

        new ReplacementRule("rho", "\\rho"),
        new ReplacementRule("tau", "\\tau"),

        new ReplacementRule("phi", "\\phi"),
        new ReplacementRule("Phi", "\\Phi"),

        new ReplacementRule("chi", "\\chi"),

        new ReplacementRule("psi", "\\psi"),
        new ReplacementRule("Psi", "\\Psi"),

        new ReplacementRule("ome", "\\omega")
    );
}