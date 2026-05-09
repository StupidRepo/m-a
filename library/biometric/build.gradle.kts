plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
}
