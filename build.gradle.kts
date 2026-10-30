plugins { kotlin("jvm") version "2.0.21" }

kotlin { jvmToolchain(17) }

sourceSets {
    main { kotlin.srcDir("core/src/main/kotlin") }
    test { kotlin.srcDir("core/src/test/kotlin") }
}

tasks.register<JavaExec>("testProjectionResolution") {
    group = "verification"; dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.nitsuya.aa.display.model.ProjectionResolutionTestKt")
}
tasks.register<JavaExec>("testShizukuPolicy") {
    group = "verification"; dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.nitsuya.aa.display.shizuku.ShizukuAccessPolicyTestKt")
}
tasks.register<JavaExec>("testResolutionCoordinator", "testProjectionPerformance", "testProjectionContentTransform") {
    group = "verification"; dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.nitsuya.aa.display.resolution.ResolutionOverrideCoordinatorTestKt")
}

tasks.register<JavaExec>("testProjectionPerformance") {
    group = "verification"; dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.nitsuya.aa.display.performance.ProjectionPerformanceProfileTestKt")
}

tasks.register("verifyHybridCore") {
    group = "verification"
    dependsOn("testProjectionResolution", "testShizukuPolicy", "testResolutionCoordinator")
}

tasks.register<JavaExec>("testProjectionContentTransform") {
    group = "verification"; dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.nitsuya.aa.display.performance.ProjectionContentTransformTestKt")
}
