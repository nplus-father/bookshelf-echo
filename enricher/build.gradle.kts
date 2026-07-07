plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.jib)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "wiki.nplus.airadar.enricher.MainKt"
}

jib {
    from { image = "eclipse-temurin:21-jre-alpine" }
    to {
        image = "ghcr.io/nplus-father/ai-radar-enricher"
        tags = setOf("latest", project.version.toString())
    }
    container {
        ports = listOf("9102")
        jvmFlags = listOf("-Duser.timezone=Asia/Taipei")
        creationTime.set("USE_CURRENT_TIMESTAMP")
        workingDirectory = "/app"
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.jsoup)
}
