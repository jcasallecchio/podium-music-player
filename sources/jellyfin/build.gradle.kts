plugins {
    alias(libs.plugins.podium.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":sources:api"))
    implementation(libs.kotlinx.serialization.json)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
