plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = "wiki.nplus.airadar"
    version = "0.1.0"
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktlint("1.5.0").editorConfigOverride(
            mapOf(
                "max_line_length" to "off",
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_function-expression-body" to "disabled",
                "ktlint_standard_multiline-expression-wrapping" to "disabled",
                "ktlint_standard_string-template-indent" to "disabled",
                "ktlint_standard_chain-method-continuation" to "disabled",
                "ktlint_standard_parameter-list-wrapping" to "disabled",
                "ktlint_standard_argument-list-wrapping" to "disabled",
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
