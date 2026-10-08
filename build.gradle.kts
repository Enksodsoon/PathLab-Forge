import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    application
}

group = "org.pathlab"
version = "1.0.0-rc.1"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.apache.poi:poi:5.5.1")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val viewerOrigin = providers.gradleProperty("pathlab.forge.viewer.defaultOrigin")
    .orElse(providers.environmentVariable("PATHLAB_FORGE_VIEWER_DEFAULT_ORIGIN"))
val featureCatalogPublicKey = providers.gradleProperty("pathlab.forge.featureCatalogPublicKey")

application {
    mainClass = "org.pathlab.forge.ForgeApp"
    applicationDefaultJvmArgs = listOf("-Xmx512m", "--enable-native-access=ALL-UNNAMED") + viewerOrigin.orNull
        ?.let { listOf("-Dpathlab.forge.viewer.defaultOrigin=$it") }
        .orEmpty()
}

tasks.register("productionDist") {
    group = "distribution"
    description = "Builds a release distribution with an explicit official Viewer origin."
    dependsOn("verifyReaderRuntimeBundle", tasks.installDist)
}

tasks.register<JavaExec>("verifyReaderRuntimeBundle") {
    group = "verification"
    description = "Portable fail-closed licensed reader and architecture verification."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.pathlab.forge.runtime.ReaderRuntimeVerifier"
    val runtimeRoot = providers.gradleProperty("pathlab.forge.readerRuntimeRoot").orElse("PENDING_REVIEW")
    args(runtimeRoot.get(), layout.projectDirectory.file("reader-runtime.lock.properties").asFile.absolutePath)
    inputs.files("reader-runtime.lock.properties")
}

val hostWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val hostMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val executableSuffix = if (hostWindows) ".exe" else ""
val hostArchitecture = when (System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "arm64"
    else -> "unsupported"
}
val readerPlatform = "${if (hostWindows) "windows" else if (hostMac) "macos" else "unsupported"}-$hostArchitecture"
val packagedApp = if (hostMac) "PathLab Forge.app/Contents/Resources" else "PathLab Forge"
val packagedRuntime = if (hostMac) "PathLab Forge.app/Contents/runtime/Contents/Home" else "PathLab Forge/runtime"

val pnpmCommand = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "pnpm.cmd" else "pnpm"
val codexNodeBin = file(
    "${System.getProperty("user.home")}/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin")
val frontendPath = if (codexNodeBin.isDirectory) {
    codexNodeBin.absolutePath + File.pathSeparator + System.getenv("PATH")
} else {
    System.getenv("PATH")
}

val frontendInstall = tasks.register<Exec>("frontendInstall") {
    workingDir = file("frontend")
    commandLine(pnpmCommand, "install", "--frozen-lockfile")
    environment("PATH", frontendPath)
    inputs.files("frontend/package.json", "frontend/pnpm-lock.yaml")
    doNotTrackState("pnpm's Windows junction-based node_modules tree is not snapshot-safe")
}

val frontendBuild = tasks.register<Exec>("frontendBuild") {
    dependsOn(frontendInstall)
    workingDir = file("frontend")
    commandLine(pnpmCommand, "run", "build")
    environment("PATH", frontendPath)
    inputs.dir("frontend/src")
    inputs.files(
        "frontend/index.html",
        "frontend/package.json",
        "frontend/pnpm-lock.yaml",
        "frontend/tsconfig.json",
        "frontend/vite.config.ts")
    outputs.dir(layout.buildDirectory.dir("frontend/web"))
}

tasks.processResources {
    dependsOn(frontendBuild)
    exclude("web/app.html", "web/app.css", "web/app.js")
    from(layout.buildDirectory.dir("frontend/web")) {
        into("web")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("file.encoding", "UTF-8")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("user.timezone", "UTC")
    systemProperty("pathlab.forge.resourceGovernor.enabled", "false")
    systemProperty(
        "pathlab.forge.runtime.processors",
        System.getProperty("pathlab.forge.runtime.processors", "6"))
    systemProperty(
        "pathlab.forge.runtime.memoryBytes",
        System.getProperty(
            "pathlab.forge.runtime.memoryBytes",
            (8L * 1024 * 1024 * 1024).toString()))
    listOf(
        "pathlab.forge.test.ome",
        "pathlab.forge.test.ome.width",
        "pathlab.forge.test.ome.height",
        "pathlab.forge.test.fullDzi",
        "pathlab.forge.test.heSource",
        "pathlab.forge.test.runtimeRoot",
        "pathlab.forge.test.mds",
        "pathlab.forge.test.sdpc",
        "pathlab.forge.sdpcRuntime",
        "pathlab.forge.test.isyntax",
        "pathlab.forge.isyntaxRuntime",
        "pathlab.forge.isyntaxPython",
        "pathlab.forge.test.svsRoots",
        "pathlab.forge.test.svsReport",
        "pathlab.forge.test.omeMetadataTarget",
    ).forEach { name ->
        System.getProperty(name)?.let { value -> systemProperty(name, value) }
    }
    reports.junitXml.mergeReruns = false
}

tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = project.version.toString()
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val installVersionedRuntime = tasks.register<Sync>("installVersionedRuntime") {
    dependsOn(tasks.installDist)
    val localAppData = providers.environmentVariable("LOCALAPPDATA")
        .orElse("${System.getProperty("user.home")}/AppData/Local")
    val runtimeVersion = providers.provider {
        System.getenv("PATHLAB_FORGE_RUNTIME_VERSION")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: project.version.toString()
    }
    from(layout.buildDirectory.dir("install/${project.name}"))
    into(localAppData.zip(runtimeVersion) { root, release ->
        file("$root/PathLab Forge/runtime/app/$release")
    })
    doLast {
        val pointer = file("${localAppData.get()}/PathLab Forge/runtime/current.txt")
        pointer.parentFile.mkdirs()
        val partial = file("${pointer.absolutePath}.partial")
        partial.writeText(runtimeVersion.get() + System.lineSeparator())
        Files.move(
            partial.toPath(),
            pointer.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
        logger.lifecycle("Installed PathLab Forge runtime ${runtimeVersion.get()} at ${destinationDir}")
    }
}

val internalReaderRoot = layout.buildDirectory.dir("internal-reader-dist")

val cleanInternalReaderDist = tasks.register<Delete>("cleanInternalReaderDist") {
    delete(internalReaderRoot)
}

val stageInternalReaderApp = tasks.register<Exec>("stageInternalReaderApp") {
    group = "distribution"
    description = "Builds a host-native application image with its own Java 17 runtime."
    dependsOn(cleanInternalReaderDist, tasks.installDist)
    val jpackage = file("${System.getProperty("java.home")}/bin/jpackage$executableSuffix")
    val input = layout.buildDirectory.dir("install/${project.name}/lib").get().asFile
    inputs.dir(input)
    doNotTrackState("jpackage requires its application-image destination not to exist")
    commandLine(
        jpackage,
        "--type", "app-image",
        "--input", input,
        "--dest", internalReaderRoot.get().asFile,
        "--name", "PathLab Forge",
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "org.pathlab.forge.ForgeApp",
        "--add-modules", "ALL-MODULE-PATH",
        "--java-options", "-Xmx512m",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        *if (hostWindows) arrayOf("--win-console") else emptyArray<String>())
}

val stageInternalReaderChildJvm = tasks.register<Copy>("stageInternalReaderChildJvm") {
    group = "distribution"
    description = "Adds the matching Java launcher required by the contained Bio-Formats child process."
    dependsOn(stageInternalReaderApp)
    from(file("${System.getProperty("java.home")}/bin/java$executableSuffix"))
    into(internalReaderRoot.map { it.dir("$packagedRuntime/bin") })
}

val assembleInternalReaderRuntime = tasks.register<JavaExec>("assembleInternalReaderRuntime") {
    group = "distribution"
    description = "Copies an owner-supplied reader runtime into an internal validation package."
    dependsOn(stageInternalReaderChildJvm, tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.pathlab.forge.runtime.ReaderRuntimeAssembler"
    val source = providers.gradleProperty("pathlab.forge.readerRuntimeRoot")
    inputs.dir(source)
    outputs.dir(internalReaderRoot.map { it.dir("$packagedApp/reader-data/runtime") })
    args(source.get(), internalReaderRoot.get().dir("$packagedApp/reader-data").asFile.absolutePath,
        "INTERNAL", readerPlatform)
}

tasks.register("internalReaderDist") {
    group = "distribution"
    description = "Builds a NON_REDISTRIBUTABLE host-native package with owner-supplied readers."
    dependsOn(assembleInternalReaderRuntime)
    outputs.dir(internalReaderRoot)
}

val productionReaderRoot = layout.buildDirectory.dir("production-reader-dist")

val cleanProductionReaderDist = tasks.register<Delete>("cleanProductionReaderDist") {
    delete(productionReaderRoot)
}

val stageProductionReaderApp = tasks.register<Exec>("stageProductionReaderApp") {
    group = "distribution"
    description = "Builds the approved host-native application image with its own Java 17 runtime."
    dependsOn(cleanProductionReaderDist, tasks.installDist, "verifyReaderRuntimeBundle")
    val jpackage = file("${System.getProperty("java.home")}/bin/jpackage$executableSuffix")
    val input = layout.buildDirectory.dir("install/${project.name}/lib").get().asFile
    val officialOrigin = viewerOrigin.orNull
    require(!officialOrigin.isNullOrBlank()) {
        "Production packaging requires pathlab.forge.viewer.defaultOrigin"
    }
    require(officialOrigin.startsWith("https://")) {
        "Production Viewer origin must use HTTPS"
    }
    inputs.dir(input)
    doNotTrackState("jpackage requires its application-image destination not to exist")
    commandLine(
        jpackage,
        "--type", "app-image",
        "--input", input,
        "--dest", productionReaderRoot.get().asFile,
        "--name", "PathLab Forge",
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "org.pathlab.forge.ForgeApp",
        "--add-modules", "ALL-MODULE-PATH",
        "--java-options", "-Xmx512m",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-Dpathlab.forge.viewer.defaultOrigin=$officialOrigin",
        "--java-options", "-Dpathlab.forge.runtime.requireProduction=true")
}

val stageProductionReaderChildJvm = tasks.register<Copy>("stageProductionReaderChildJvm") {
    group = "distribution"
    description = "Adds the matching Java launcher for the approved Bio-Formats child process."
    dependsOn(stageProductionReaderApp)
    from(file("${System.getProperty("java.home")}/bin/java$executableSuffix"))
    into(productionReaderRoot.map { it.dir("$packagedRuntime/bin") })
}

val assembleProductionReaderRuntime = tasks.register<JavaExec>("assembleProductionReaderRuntime") {
    group = "distribution"
    description = "Assembles an approved, hash-locked production reader runtime."
    dependsOn(stageProductionReaderChildJvm, tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.pathlab.forge.runtime.ReaderRuntimeAssembler"
    val source = providers.gradleProperty("pathlab.forge.readerRuntimeRoot")
    inputs.dir(source)
    outputs.dir(productionReaderRoot.map { it.dir("$packagedApp/reader-data/runtime") })
    args(source.get(), productionReaderRoot.get().dir("$packagedApp/reader-data").asFile.absolutePath,
        "PRODUCTION", readerPlatform)
}

tasks.named("productionDist") {
    setDependsOn(listOf(assembleProductionReaderRuntime))
    outputs.dir(productionReaderRoot)
}

// The Electron launcher uses this self-contained service; no system Java lookup.
tasks.register<Sync>("stageElectronService") {
    group = "distribution"
    dependsOn(tasks.installDist)
    val servicePlatform = readerPlatform
    val serviceWindows = hostWindows
    val serviceArchitecture = hostArchitecture
    val serviceOrigin = viewerOrigin.orNull
    val serviceChannel = providers.gradleProperty("pathlab.forge.distributionChannel").orNull
    val serviceVersion = project.version.toString()
    val desktopMetadata = layout.projectDirectory.file("desktop/package.json").asFile
    inputs.property("distributionChannel", serviceChannel ?: "MISSING")
    inputs.property("viewerOrigin", serviceOrigin ?: "MISSING")
    inputs.property("featureCatalogPublicKey", featureCatalogPublicKey.orNull ?: "MISSING")
    inputs.property("serviceVersion", serviceVersion)
    inputs.property("servicePlatform", servicePlatform)
    if (serviceChannel == "PRODUCTION") dependsOn("verifyReaderRuntimeBundle")
    into(layout.projectDirectory.dir("desktop/resources/service"))
    from(layout.buildDirectory.dir("install/${project.name}/lib")) { into("lib") }
    from(System.getProperty("java.home")) { into("runtime") }
    doFirst {
        require(servicePlatform in listOf("windows-x86_64", "macos-x86_64", "macos-arm64")) {
            "Unsupported packaged service platform: $servicePlatform"
        }
        require(JavaVersion.current() == JavaVersion.VERSION_17) { "Package service with Java 17" }
        val desktopVersion = (JsonSlurper().parse(desktopMetadata) as Map<*, *>)["version"]
        require(serviceVersion == desktopVersion) { "Java and Electron release versions must match" }
        require(serviceChannel in listOf("INTERNAL", "PRODUCTION")) {
            "Explicit pathlab.forge.distributionChannel=INTERNAL or PRODUCTION is required"
        }
        if (serviceChannel == "PRODUCTION") {
            require(!serviceOrigin.isNullOrBlank()) { "Production requires an explicit official Viewer origin" }
            val encoded = featureCatalogPublicKey.orNull
            require(!encoded.isNullOrBlank()) { "Production requires an approved feature catalog public key" }
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        }
    }
    doLast {
        val manifest = mutableMapOf<String, Any>(
            "version" to serviceVersion,
            "platform" to if (serviceWindows) "win32" else "darwin",
            "arch" to if (serviceArchitecture == "arm64") "arm64" else "x64",
            "internalValidation" to (serviceChannel == "INTERNAL"),
            "distribution" to if (serviceChannel == "INTERNAL") "INTERNAL_NON_REDISTRIBUTABLE" else "PRODUCTION",
            "requireProductionRuntime" to (serviceChannel == "PRODUCTION"))
        serviceOrigin?.let { origin ->
            val uri = URI(origin)
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) {
                "Packaged Viewer origin requires an HTTPS host without credentials"
            }
            manifest["viewerOrigin"] = origin
        }
        featureCatalogPublicKey.orNull?.let { manifest["featureCatalogPublicKey"] = it }
        destinationDir.resolve("runtime-manifest.json").writeText(JsonOutput.toJson(manifest) + "\n")
    }
}

tasks.register("distributionDependencies") {
    group = "distribution"
    description = "Inventories exact resolved Java artifacts and their embedded legal text; no license approval."
    dependsOn(tasks.installDist)
    doLast {
        val output = layout.buildDirectory.dir("distribution-inputs").get().asFile
        output.mkdirs()
        val notices = output.resolve("java-notices")
        notices.mkdirs()
        val records = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }.map { artifact ->
                val digest = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                val destination = notices.resolve(digest)
                copy {
                    from(zipTree(artifact.file))
                    include("**/LICENSE*", "**/NOTICE*", "**/COPYING*", "**/license*", "**/notice*")
                    into(destination)
                }
                mapOf("coordinate" to artifact.moduleVersion.id.toString(), "file" to artifact.file.name,
                    "sha256" to digest, "decision" to "PENDING_REVIEW",
                    "legalFiles" to destination.walkTopDown().filter { it.isFile }.map { it.relativeTo(output).invariantSeparatorsPath }.toList())
            }
        output.resolve("java-dependencies.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(records)) + "\n")
    }
}

tasks.register<Exec>("signElectronService") {
    group = "distribution"
    description = "Signs the native production service before final byte inventory; trusted identities required."
    dependsOn("stageElectronService")
    environment("PATH", frontendPath)
    commandLine("node", "scripts/sign-distribution-service.cjs", "desktop/resources/service", "build/distribution-inputs/service-signature.json")
}

tasks.register<Exec>("distributionInventory") {
    group = "distribution"
    description = "Creates clean-commit source and staged-byte receipts, explicitly NON_REDISTRIBUTABLE."
    dependsOn("stageElectronService", "distributionDependencies")
    if (providers.gradleProperty("pathlab.forge.distributionChannel").orNull == "PRODUCTION") dependsOn("signElectronService")
    environment("PATH", frontendPath)
    commandLine("node", "scripts/distribution.cjs", "inventory", "build/distribution-inputs",
        "desktop/resources/service", "${if (hostWindows) "win32" else "darwin"}-${if (hostArchitecture == "arm64") "arm64" else "x64"}")
}
