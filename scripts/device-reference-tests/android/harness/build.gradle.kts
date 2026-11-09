import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins { id("com.android.application") }

val generatedAssets = layout.buildDirectory.dir("generated/referenceAssets")
val prepareReferenceSheet = tasks.register("prepareReferenceSheet") {
    val source = providers.gradleProperty("referenceSheet")
    inputs.property("referenceSheet", source.orElse(""))
    source.orNull?.let { inputs.file(it) }
    outputs.dir(generatedAssets)
    doLast {
        require(source.isPresent) { "Pass -PreferenceSheet=/tmp/sequo-device-reference-sheet.json; no invented/default data is used." }
        val input = JsonSlurper().parse(file(source.get())) as Map<*, *>
        require(input["environment"] == "LOCAL_FIXTURE" && input["businessWrites"] == false && input["providerOperations"] == false) {
            "Only the sanitized, read-only LOCAL_FIXTURE sheet is accepted."
        }
        val roles = input["roles"] as Map<*, *>
        fun role(key: String, family: String): Map<*, *> {
            val value = roles[key] as Map<*, *>
            require(value["family"] == family)
            require(((value["user"] as Map<*, *>)["id"] as String).startsWith("local-ops-v1-"))
            return value
        }
        fun rows(role: Map<*, *>, key: String) = (role[key] as List<*>).map { it as Map<*, *> }
        fun ref(row: Map<*, *>): String = (row["publicReference"] as String).also {
            require(it.matches(Regex("[0-9A-Z]{3}-[0-9A-Z]{3}-[0-9A-Z]{3}")))
        }
        val ama = role("ama", "SEQUO_APP")
        val yawa = role("yawa", "SEQUO_VENDOR")
        val senam = role("senam", "SEQUO_RIDER")
        val esso = role("esso", "SEQUO_HUB")
        role("dede", "SEQUO_HUB")
        val sharedId = "SQ-local-ops-v1-merchant-preparing"
        val mission = rows(senam, "missions").single { it["orderId"] == sharedId }
        val parcelRows = rows(esso, "parcels")
        require(parcelRows.size == 3 && parcelRows.all { (it["id"] as String).startsWith("local-ops-v1-parcel-") })
        val reduced = mapOf(
            "protocol" to "SEQUO_REFERENCE_UI_V1", "environment" to "LOCAL_FIXTURE",
            "sourceCommit" to input["sourceCommit"],
            "customerRecent" to ref(rows(ama, "orders").single { it["id"] == "SQ-local-ops-v1-return-refund" }),
            "sharedOrder" to ref(rows(ama, "orders").single { it["id"] == sharedId }),
            "vendorPart" to ref(rows(yawa, "parts").single { it["orderId"] == sharedId }),
            "riderMission" to ref(mission),
            "riderLedger" to ref(rows(senam, "ledger").single { it["missionId"] == mission["id"] && it["status"] == "AVAILABLE" }),
            "returnLookup" to ref(rows(ama, "returns").single { it["id"] == "local-ops-v1-return-request-awaiting-dropoff" }),
            "parcelReferences" to parcelRows.map(::ref),
        )
        val output = generatedAssets.get().file("reference-sheet.json").asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.toJson(reduced))
    }
}

android {
    namespace = "dev.orestegabo.sequo.devicereferencetests"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.orestegabo.sequo.devicereferencetests.fixture"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["fixtureMode"] = "LOCAL_FIXTURE"
        testInstrumentationRunnerArguments["expectedSerial"] = "emulator-5554"
    }
    sourceSets.getByName("androidTest").assets.srcDir(generatedAssets.get().asFile)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { animationsDisabled = true }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("AndroidTestAssets")) dependsOn(prepareReferenceSheet)
}

dependencies {
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
