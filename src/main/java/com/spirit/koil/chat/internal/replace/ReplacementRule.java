package com.spirit.koil.chat.internal.replace;

import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;

import java.util.List;

public class ReplacementRule {

    public enum Type {
        LITERAL,
        REGEX,
        EXPRESSION,

        COMMAND,
        ACCENT,
        FONT
    }

    public final String trigger;
    public final String replacement;
    public final Type type;
    public final boolean regex;

    public ReplacementRule(String trigger, String replacement) {
        this(trigger, replacement, Type.LITERAL);
    }

    public ReplacementRule(String trigger, String replacement, Type type) {
        this.trigger = trigger;
        this.replacement = replacement;
        this.type = type;
        this.regex = type == Type.REGEX
                || type == Type.COMMAND
                || type == Type.ACCENT
                || type == Type.FONT;
    }

    public static final List<ReplacementRule> RULES = List.of(
            new ReplacementRule("@([A-Za-z]+)", "", Type.COMMAND),

            new ReplacementRule(
                    "([A-Za-z0-9]+)(dot|ddot|dddot|ddddot|hat|widehat|bar|overline|vec|tilde|widetilde|underline|overbrace|underbrace|breve|check|acute|grave|mathring|cancel|bcancel|xcancel)",
                    "",
                    Type.ACCENT
            ),

            new ReplacementRule(
                    "([A-Za-z0-9]+)(bb|bf|cal|scr|frak|sf|tt|rm|it|bm)",
                    "",
                    Type.FONT
            ),

            new ReplacementRule("mk", "$|$"),
            new ReplacementRule("dm", "$$|$$"),

            new ReplacementRule("//", "\\frac{|}{|}", Type.EXPRESSION),
            new ReplacementRule("frac", "\\frac{|}{|}", Type.EXPRESSION),

            new ReplacementRule("sqrt", "\\sqrt{|}", Type.EXPRESSION),
            new ReplacementRule("sq", "\\sqrt{|}", Type.EXPRESSION),
            new ReplacementRule("cb", "\\sqrt[3]{|}", Type.EXPRESSION),
            new ReplacementRule("fourth", "\\sqrt[4]{|}", Type.EXPRESSION),

            new ReplacementRule("_", "_{|}", Type.EXPRESSION),
            new ReplacementRule("__", "_{|}", Type.EXPRESSION),

            new ReplacementRule("text", "\\text{|}", Type.EXPRESSION),
            new ReplacementRule("\"", "\\text{|}", Type.EXPRESSION),

            new ReplacementRule("bf", "\\mathbf{|}", Type.EXPRESSION),
            new ReplacementRule("bb", "\\mathbb{|}", Type.EXPRESSION),
            new ReplacementRule("cal", "\\mathcal{|}", Type.EXPRESSION),
            new ReplacementRule("scr", "\\mathscr{|}", Type.EXPRESSION),
            new ReplacementRule("frak", "\\mathfrak{|}", Type.EXPRESSION),
            new ReplacementRule("sf", "\\mathsf{|}", Type.EXPRESSION),
            new ReplacementRule("tt", "\\mathtt{|}", Type.EXPRESSION),
            new ReplacementRule("rm", "\\mathrm{|}", Type.EXPRESSION),
            new ReplacementRule("it", "\\mathit{|}", Type.EXPRESSION),
            new ReplacementRule("bm", "\\bm{|}", Type.EXPRESSION),

            new ReplacementRule("abs", "\\left|{|}\\right|", Type.EXPRESSION),
            new ReplacementRule("norm", "\\left\\lVert{|}\\right\\rVert", Type.EXPRESSION),
            new ReplacementRule("ceil", "\\left\\lceil{|}\\right\\rceil", Type.EXPRESSION),
            new ReplacementRule("floor", "\\left\\lfloor{|}\\right\\rfloor", Type.EXPRESSION),

            new ReplacementRule("()", "\\left({|}\\right)", Type.EXPRESSION),
            new ReplacementRule("[]", "\\left[{|}\\right]", Type.EXPRESSION),
            new ReplacementRule("{}", "\\left\\{{|}\\right\\}", Type.EXPRESSION),

            new ReplacementRule("vec2", "\\begin{bmatrix}|\\\\|\\end{bmatrix}"),
            new ReplacementRule("vec3", "\\begin{bmatrix}|\\\\|\\\\|\\end{bmatrix}"),
            new ReplacementRule("mat2", "\\begin{bmatrix}|&|\\\\|&|\\end{bmatrix}"),
            new ReplacementRule("cases", "\\begin{cases}|\\\\|\\end{cases}"),

            new ReplacementRule("sum", "\\sum_{|}^{|}"),
            new ReplacementRule("prod", "\\prod_{|}^{|}"),
            new ReplacementRule("int", "\\int_{|}^{|}"),
            new ReplacementRule("iint", "\\iint_{|}^{|}"),
            new ReplacementRule("iiint", "\\iiint_{|}^{|}"),
            new ReplacementRule("oint", "\\oint_{|}^{|}"),

            new ReplacementRule("lim", "\\lim_{|}"),
            new ReplacementRule("limsup", "\\limsup_{|}"),
            new ReplacementRule("liminf", "\\liminf_{|}"),

            new ReplacementRule("sin", "\\sin"),
            new ReplacementRule("cos", "\\cos"),
            new ReplacementRule("tan", "\\tan"),
            new ReplacementRule("cot", "\\cot"),
            new ReplacementRule("sec", "\\sec"),
            new ReplacementRule("csc", "\\csc"),

            new ReplacementRule("arcsin", "\\arcsin"),
            new ReplacementRule("arccos", "\\arccos"),
            new ReplacementRule("arctan", "\\arctan"),

            new ReplacementRule("log", "\\log"),
            new ReplacementRule("ln", "\\ln"),
            new ReplacementRule("exp", "\\exp"),

            new ReplacementRule("det", "\\det"),
            new ReplacementRule("dim", "\\dim"),
            new ReplacementRule("deg", "\\deg"),

            new ReplacementRule("oo", "\\infty"),
            new ReplacementRule("inf", "\\infty"),

            new ReplacementRule("pm", "\\pm"),
            new ReplacementRule("+-", "\\pm"),
            new ReplacementRule("-+", "\\mp"),

            new ReplacementRule("xx", "\\times"),
            new ReplacementRule("**", "\\cdot"),
            new ReplacementRule("div", "\\div"),

            new ReplacementRule("!=", "\\neq"),
            new ReplacementRule("<=", "\\leq"),
            new ReplacementRule(">=", "\\geq"),
            new ReplacementRule("==", "\\equiv"),

            new ReplacementRule("->", "\\rightarrow"),
            new ReplacementRule("<-", "\\leftarrow"),
            new ReplacementRule("<->", "\\leftrightarrow"),
            new ReplacementRule("=>", "\\Rightarrow"),
            new ReplacementRule("<=>", "\\Leftrightarrow"),
            new ReplacementRule("|->", "\\mapsto"),

            new ReplacementRule("...", "\\ldots"),
            new ReplacementRule("c...", "\\cdots"),
            new ReplacementRule("v...", "\\vdots"),
            new ReplacementRule("d...", "\\ddots"),

            new ReplacementRule("oo", "\\infty"),
            new ReplacementRule("aleph", "\\aleph"),
            new ReplacementRule("hbar", "\\hbar"),
            new ReplacementRule("ell", "\\ell"),
            new ReplacementRule("wp", "\\wp"),

            new ReplacementRule("@a", "\\alpha"),
            new ReplacementRule("@b", "\\beta"),
            new ReplacementRule("@g", "\\gamma"),
            new ReplacementRule("@d", "\\delta"),
            new ReplacementRule("@e", "\\epsilon"),
            new ReplacementRule("@z", "\\zeta"),
            new ReplacementRule("@h", "\\eta"),
            new ReplacementRule("@t", "\\theta"),
            new ReplacementRule("@i", "\\iota"),
            new ReplacementRule("@k", "\\kappa"),
            new ReplacementRule("@l", "\\lambda"),
            new ReplacementRule("@m", "\\mu"),
            new ReplacementRule("@n", "\\nu"),
            new ReplacementRule("@x", "\\xi"),
            new ReplacementRule("@p", "\\pi"),
            new ReplacementRule("@r", "\\rho"),
            new ReplacementRule("@s", "\\sigma"),
            new ReplacementRule("@u", "\\tau"),
            new ReplacementRule("@f", "\\phi"),
            new ReplacementRule("@c", "\\chi"),
            new ReplacementRule("@y", "\\psi"),
            new ReplacementRule("@o", "\\omega"),

            new ReplacementRule("@G", "\\Gamma"),
            new ReplacementRule("@D", "\\Delta"),
            new ReplacementRule("@T", "\\Theta"),
            new ReplacementRule("@L", "\\Lambda"),
            new ReplacementRule("@X", "\\Xi"),
            new ReplacementRule("@P", "\\Pi"),
            new ReplacementRule("@S", "\\Sigma"),
            new ReplacementRule("@F", "\\Phi"),
            new ReplacementRule("@Y", "\\Psi"),
            new ReplacementRule("@W", "\\Omega"),

            new ReplacementRule("(", "(|)"),
            new ReplacementRule("{", "{|}"),
            new ReplacementRule("[", "[|]")
    );
}