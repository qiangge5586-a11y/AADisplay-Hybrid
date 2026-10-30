plugins { kotlin("jvm") version "2.0.21" }

kotlin { jvmToolchain(17) }

sourceSets {
    main { kotlin.srcDir("core/src/main/kotlin") }
    test { kotlin.srcDir("core/src/test/kotlin") }
}

fun registerCoreTest(taskName: String, testMainClass: String) {
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        dependsOn("testClasses")
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(testMainClass)
    }
}

registerCoreTest("testProjectionResolution", "io.github.nitsuya.aa.display.model.ProjectionResolutionTestKt")
registerCoreTest("testShizukuPolicy", "io.github.nitsuya.aa.display.shizuku.ShizukuAccessPolicyTestKt")
registerCoreTest("testResolutionCoordinator", "io.github.nitsuya.aa.display.resolution.ResolutionOverrideCoordinatorTestKt")
registerCoreTest("testProjectionPerformance", "io.github.nitsuya.aa.display.performance.ProjectionPerformanceProfileTestKt")
registerCoreTest("testProjectionContentTransform", "io.github.nitsuya.aa.display.performance.ProjectionContentTransformTestKt")

tasks.register("verifyHybridCore") {
    group = "verification"
    dependsOn(
        "testProjectionResolution",
        "testShizukuPolicy",
        "testResolutionCoordinator",
        "testProjectionPerformance",
        "testProjectionContentTransform"
    )
}
