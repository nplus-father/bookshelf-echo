plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.coroutines)
    api(libs.kotlinx.serialization.json)
    api(libs.amqp.client)
    api(libs.hikari)
    api(libs.logback.classic)
    api(libs.micrometer.prometheus)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}
