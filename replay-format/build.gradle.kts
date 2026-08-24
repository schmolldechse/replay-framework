plugins {
    `java-library`
}

dependencies {
    implementation(libs.gson)
    implementation(libs.zstd.jni)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
