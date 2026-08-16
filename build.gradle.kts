plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = "wiki.nplus.airadar"
    version = "0.1.0"
}

// Formatter, not a style tribunal. ktlint's mechanical rules (imports, blank
// lines, indentation, trailing whitespace) are worth enforcing because nobody
// argues about them; its line-length and wrapping rules are not, because this
// codebase deliberately carries long explanatory comments and a reflow would
// bury every real diff under them. Hence the disabled rules below — each one
// is a decision, not an oversight.
spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktlint("1.5.0").editorConfigOverride(
            mapOf(
                // The comments here ARE the documentation and several are
                // deliberately wrapped mid-expression for readability; a column
                // limit would churn every file and settle nothing.
                "max_line_length" to "off",
                // ...but with no column limit ktlint's layout rules conclude
                // everything fits on one line and start JOINING wrapped code —
                // turning a two-line signature into a 160-column one. Off.
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_function-expression-body" to "disabled",
                "ktlint_standard_multiline-expression-wrapping" to "disabled",
                "ktlint_standard_string-template-indent" to "disabled",
                "ktlint_standard_chain-method-continuation" to "disabled",
                "ktlint_standard_parameter-list-wrapping" to "disabled",
                "ktlint_standard_argument-list-wrapping" to "disabled",
                // Trailing commas are already the house style.
                "ij_kotlin_allow_trailing_comma" to "true",
                "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
            ),
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint("1.5.0").editorConfigOverride(mapOf("max_line_length" to "off"))
    }
}
