plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "wiki.nplus.airadar.ops.MainKt"
}

dependencies {
    implementation(project(":common"))
}
