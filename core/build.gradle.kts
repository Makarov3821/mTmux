plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.github.mwiede:jsch:2.28.7")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    testImplementation(kotlin("test-junit"))
}
