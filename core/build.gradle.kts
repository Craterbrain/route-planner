plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Single source of truth for the address normalizer contract (shared with tools/addrnorm.py).
    val cases = rootProject.file("tools/address-normalize-cases.json")
    inputs.file(cases)
    systemProperty("addressCases", cases.absolutePath)
    // Optional extra replay fixture kept outside the repo: -PaddressFixture=/path/fixture.json.gz
    (project.findProperty("labelCorpus") as String?)?.let { systemProperty("labelCorpus", it) }
    (project.findProperty("addressFixture") as String?)?.let { systemProperty("addressFixture", it) }
}
