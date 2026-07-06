plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "wiki.nplus.airadar.ops.MainKt"
}

dependencies {
    implementation(project(":common"))
}
