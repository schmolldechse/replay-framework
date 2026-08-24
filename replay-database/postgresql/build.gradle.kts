plugins {
    `java-library`
}

dependencies {
    implementation(project(":replay-api"))
    implementation(project(":replay-core"))
    implementation(libs.gson)
    implementation(libs.guice)
    implementation(libs.hikari)
    implementation(libs.hibernate.core)
    implementation(libs.flyway.core)
    compileOnly(libs.paper.api)
    runtimeOnly("org.flywaydb:flyway-database-postgresql:13.3.0")
    runtimeOnly(libs.postgresql)
    testCompileOnly(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.paper.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
