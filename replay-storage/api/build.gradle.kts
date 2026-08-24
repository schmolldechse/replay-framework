plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":replay-api"))
    testFixturesImplementation(project(":replay-api"))
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesRuntimeOnly(libs.junit.platform.launcher)
}
