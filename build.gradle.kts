import com.github.jengelman.gradle.plugins.shadow.transformers.Transformer
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.minecraftforge.gradle.userdev.UserDevExtension
import net.minecraftforge.gradle.userdev.tasks.RenameJarInPlace
import org.apache.tools.zip.ZipEntry
import org.apache.tools.zip.ZipOutputStream
import org.codehaus.plexus.util.IOUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter

buildscript {
    repositories {
        maven("https://maven.minecraftforge.net/") {
            name = "Forge"
        }
        maven("https://repo.spongepowered.org/maven") {
            name = "Sponge Mixin"
        }
        //maven("https://jitpack.io")
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("net.minecraftforge.gradle:ForgeGradle:6.0.+") { isChanging = true }
        classpath("com.github.johnrengelman:shadow:8.1.1")
        classpath("org.spongepowered:mixingradle:0.7-SNAPSHOT")
    }
}

plugins {
    id("net.fabricmc.fabric-loom-remap") apply false
    id("net.fabricmc.fabric-loom") apply false
}

enum class SubprojectType(val isMod: Boolean) {
    CORE(false), FORGE(true), FABRIC(true), LIBRARY(false)
}

subprojects {

    val modLoaderName = project.property("modLoaderName").toString()
    val subprojectType = {
        if      (modLoaderName == "core")            SubprojectType.CORE
        else if (modLoaderName.startsWith("forge"))  SubprojectType.FORGE
        else if (modLoaderName.startsWith("fabric")) SubprojectType.FABRIC
        else                                         SubprojectType.LIBRARY
    }()

    // Match version
    val mcVersionMatcher = project.findProperty("minecraftVersion")?.toString()?.let { Regex("""^(\d+)\.(.+)$""").find(it) }
    val mcVersion = mcVersionMatcher?.groupValues?.get(2)?.toDoubleOrNull()
    val isUnobfuscated = false // sc.current.parsed.matches(">=26.1-alpha")

    apply(plugin = "java")
    if (subprojectType == SubprojectType.CORE || subprojectType == SubprojectType.LIBRARY) {
        apply(plugin = "maven-publish")
    }
    else if (subprojectType == SubprojectType.FORGE) {
        apply(plugin = "net.minecraftforge.gradle")
        apply(plugin = "org.spongepowered.mixin")
        apply(plugin = "com.github.johnrengelman.shadow")
    }
    else if (subprojectType == SubprojectType.FABRIC) {
        if (isUnobfuscated) {
            apply(plugin = "net.fabricmc.fabric-loom")
        }
        else {
            apply(plugin = "net.fabricmc.fabric-loom-remap")
        }
    }

    val (javaVersionInteger, javaVersionEnum) = {
        if      (mcVersion == null || mcVersion >= 20.5) 21 to JavaVersion.VERSION_21
        else if (mcVersion >= 18  ) 17 to JavaVersion.VERSION_17
        else                         8 to JavaVersion.VERSION_1_8
    }()
    println("Java version set to $javaVersionEnum for $project")

    if (javaVersionInteger != 8) {
        tasks.withType<JavaCompile>().configureEach {
            options.release = javaVersionInteger
        }
    }

    extensions.configure<JavaPluginExtension> {
        if (subprojectType == SubprojectType.FABRIC) {
            withSourcesJar()
        }
        toolchain.languageVersion = JavaLanguageVersion.of(javaVersionInteger)
        sourceCompatibility = javaVersionEnum
        targetCompatibility = javaVersionEnum
    }

    val mainSourceSet = extensions.getByType<JavaPluginExtension>().sourceSets["main"]

    if (subprojectType == SubprojectType.FABRIC) {
        configure<LoomGradleExtensionAPI> {
            splitEnvironmentSourceSets()

            mods {
                create("bteterrarenderer") {
                    sourceSet(mainSourceSet)
                    sourceSet("client")
                }
            }

            val accessWidener = file("src/main/resources/${project.property("mod_id")}.accesswidener")
            if (accessWidener.exists()) {
                accessWidenerPath = accessWidener
            }
        }

        tasks.named<Jar>("jar") { }
    }

    configurations {
        val shadowDep by creating
        val compileAndTestOnly by creating

        named("implementation") { extendsFrom(shadowDep) }
        named("compileOnly") { extendsFrom(compileAndTestOnly) }
        named("testImplementation") { extendsFrom(compileAndTestOnly) }
        if (subprojectType == SubprojectType.FABRIC) {
            named("include") { extendsFrom(shadowDep) }
        }
    }

    dependencies {
        // Fix javax.annotation.Nonnull + friends
        "compileOnly"("com.google.code.findbugs:jsr305:3.0.2")
        "testCompileOnly"("com.google.code.findbugs:jsr305:3.0.2")

        if (modLoaderName != "common") "shadowDep"(project(":common"))
        if (modLoaderName != "common" && modLoaderName != "mcconnector") {
            "shadowDep"(project(":mcconnector"))
        }
        if (modLoaderName == "ogc3dtiles") {
            "shadowDep"(project(":draco"))
        }

        // Mod projects depend on core
        if (subprojectType.isMod) {
            "shadowDep"(project(":core"))
            "shadowDep"(project(":ogc3dtiles"))
            "shadowDep"(project(":draco"))
            "shadowDep"(project(":terraplusplus"))
            "shadowDep"(project(":ogc3dtiles"))
            "shadowDep"(project(":draco"))
        }

        // Fabric deps (ONLY ONCE)
        if (subprojectType == SubprojectType.FABRIC) {
            "minecraft"("com.mojang:minecraft:${project.findProperty("minecraftVersion")}")
            "mappings"("net.fabricmc:yarn:${project.property("yarnMappings")}:v2")

            val myModImplementation = if (isUnobfuscated) "implementation" else "modImplementation"

            myModImplementation("net.fabricmc:fabric-loader:${rootProject.property("fabricLoaderVersion")}")

            // Fabric API (bundle)
            myModImplementation      ("net.fabricmc.fabric-api:fabric-api:${project.property("fabricVersion")}")
            // With splitEnvironmentSourceSets(), also add it to the client source set
            "modClientImplementation"("net.fabricmc.fabric-api:fabric-api:${project.property("fabricVersion")}")
        }
        else if (subprojectType == SubprojectType.FORGE) {
            "minecraft"("net.minecraftforge:forge:${project.findProperty("minecraftVersion")}-${project.property("forgeVersion")}")
        }

        // Shadow deps
        "shadowDep"("com.fasterxml.jackson.core:jackson-annotations:2.14.2")
        "shadowDep"("com.fasterxml.jackson.core:jackson-core:2.14.2")
        "shadowDep"("com.fasterxml.jackson.core:jackson-databind:2.14.2")
        "shadowDep"("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.14.2")
        "shadowDep"("de.javagl:jgltf-impl-v2:2.0.3")
        "shadowDep"("de.javagl:jgltf-model:2.0.3")
        "shadowDep"("net.daporkchop.lib:common:0.5.7-SNAPSHOT") { exclude(group = "io.netty") }
        "shadowDep"("net.daporkchop.lib:binary:0.5.7-SNAPSHOT") { exclude(group = "io.netty") }
        "shadowDep"("net.daporkchop.lib:unsafe:0.5.7-SNAPSHOT")
        "shadowDep"("org.apache.xmlgraphics:batik-anim:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-awt-util:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-bridge:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-codec:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-constants:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-css:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-dom:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-ext:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-gvt:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-i18n:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-parser:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-script:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-svg-dom:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-transcoder:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-util:1.17")
        "shadowDep"("org.apache.xmlgraphics:batik-xml:1.17")
        "shadowDep"("xml-apis:xml-apis-ext:1.3.04")
        "shadowDep"("org.osgeo:proj4j:0.1.0")
        "shadowDep"("org.yaml:snakeyaml:1.33")

        if (mcVersion != null && mcVersion > 12) { // for T++
            "shadowDep"("lzma:lzma:0.0.1")
        }
        if (mcVersion != null && mcVersion < 19) {
            "shadowDep"("org.joml:joml:1.10.8") {
                exclude(group = "org.jetbrains", module = "annotations")
            }
        }
        if (mcVersion != null && mcVersion >= 19) {
            "shadowDep"("io.netty:netty-codec-http:4.1.9.Final")
            "shadowDep"("io.netty:netty-codec-http2:4.1.9.Final")
            "shadowDep"("org.apache.xmlgraphics:xmlgraphics-commons:2.9")
            "shadowDep"("org.w3c.css:sac:1.3")
        }

        // Compile/test-only deps
        "compileAndTestOnly"("org.apache.logging.log4j:log4j-core:2.20.0")
        "compileAndTestOnly"("org.apache.commons:commons-lang3:3.12.0")
        "compileAndTestOnly"("commons-codec:commons-codec:1.16.0")
        "compileAndTestOnly"("com.google.guava:guava:31.1-jre")
        "compileAndTestOnly"("io.netty:netty-all:4.1.9.Final")
        "compileAndTestOnly"("lzma:lzma:0.0.1")
        if (!subprojectType.isMod) {
            "compileAndTestOnly"("org.joml:joml:1.10.8")
        }

        // Lombok
        "compileOnly"("org.projectlombok:lombok:1.18.32")
        "testCompileOnly"("org.projectlombok:lombok:1.18.32")
        "annotationProcessor"("org.projectlombok:lombok:1.18.32")

        if (subprojectType == SubprojectType.FORGE) {
            "annotationProcessor"("org.spongepowered:mixin:0.8.5:processor")
            "testImplementation"("org.spongepowered:lwts:1.0.0")
            "testImplementation"("org.spongepowered:mixin:0.8.5")
            "testAnnotationProcessor"("org.spongepowered:mixin:0.8.5:processor")
        }

        // Tests
        "testImplementation"("junit:junit:4.13.2")
        "testImplementation"("org.junit.jupiter:junit-jupiter-api:5.8.2")
        "testImplementation"("org.apache.logging.log4j:log4j-core:2.20.0")
        "testRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.8.2")
        "testRuntimeOnly"("junit:junit:4.13.2")
    }

    // Forge-only shading/relocation
    if (subprojectType == SubprojectType.FORGE) {

        configure<UserDevExtension> {
            mappings(project.property("mappingsChannel").toString(), project.property("mappingsVersion").toString())

            val accessTransformerPath = file("src/main/resources/META-INF/accesstransformer.cfg")
            if (accessTransformerPath.exists()) {
                accessTransformer(accessTransformerPath)
            }

            runs {
                create("client") {
                    workingDirectory(project.file("run"))
                    property("forge.logging.markers", "REGISTRIES")
                    property("forge.logging.console.level", "debug")
                    mods { create("bteterrarenderer") { source(mainSourceSet) } }
                }

                create("server") {
                    workingDirectory(project.file("run"))
                    property("forge.logging.markers", "REGISTRIES")
                    property("forge.logging.console.level", "debug")
                    mods { create("bteterrarenderer") { source(mainSourceSet) } }
                }

                create("gameTestServer") {
                    workingDirectory(project.file("run"))
                    property("forge.logging.markers", "REGISTRIES")
                    property("forge.logging.console.level", "debug")
                    mods { create("bteterrarenderer") { source(mainSourceSet) } }
                }

                create("data") {
                    workingDirectory(project.file("run"))
                    property("forge.logging.markers", "REGISTRIES")
                    property("forge.logging.console.level", "debug")
                    args("--mod", "bteterrarenderer", "--all",
                            "--output", file("src/generated/resources/"),
                            "--existing", file("src/main/resources/"))
                    mods { create("bteterrarenderer") { source(mainSourceSet) } }
                }
            }
        }

        tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
            configurations = listOf(project.configurations["shadowDep"])

            val dependenciesLocation = "${project.property("mod_group")}.${project.property("mod_id")}.dep"
            val dependencyReplacements = mapOf(
                    "com.fasterxml.jackson" to      "jackson",
                    "de.javagl.jgltf" to            "jgltf",
                    "net.daporkchop.lib" to         "porklib",
                    "org.apache.commons.io" to      "apache.commons.io",
                    "org.apache.commons.logging" to "apache.commons.logging",
                    "org.apache.xmlgraphics" to     "xmlgraphics",
                    "org.apache.batik" to           "batik",
                    "org.apache.xmlcommons" to      "xmlcommons",
                    "org.joml" to                   "joml",
                    "org.osgeo.proj4j" to           "proj4j",
                    "org.w3c.css.sac" to            "w3ccss.sac",
                    "org.w3c.dom.smil" to           "w3cdom.smil",
                    "org.w3c.dom.svg" to            "w3cdom.svg",
                    "org.yaml.snakeyaml" to         "snakeyaml"
            ).mapValues { (_, v) -> "$dependenciesLocation.$v" }

            dependencyReplacements.forEach { (k, v) -> relocate(k, v) }
            transform(ReplacePropertyContentTransformer::class.java) {
                replacements = dependencyReplacements
            }

            archiveClassifier = null
            exclude("**/module-info.class")
            exclude("license/**/*")
            exclude("about_files/**/*")
            exclude("about.html")
            exclude("plugin.properties")
            exclude("kotlin/**/*")
            exclude("javax/xml/**/*")
            exclude("org/w3c/dom/bootstrap/**/*")
            exclude("org/w3c/dom/css/**/*")
            exclude("org/w3c/dom/events/**/*")
            exclude("org/w3c/dom/html/**/*")
            exclude("org/w3c/dom/ls/**/*")
            exclude("org/w3c/dom/ranges/**/*")
            exclude("org/w3c/dom/stylesheets/**/*")
            exclude("org/w3c/dom/traversal/**/*")
            exclude("org/w3c/dom/views/**/*")
            exclude("org/w3c/dom/xpath/**/*")
            exclude("org/w3c/dom/*")
            exclude("org/xml/sax/**/*")
        }

        extensions.configure<NamedDomainObjectContainer<RenameJarInPlace>>("reobfJar") { create("shadowJar") }
        tasks.named("shadowJar").configure { dependsOn("reobfJar") }
        tasks.named("build").configure { dependsOn("shadowJar") }

        mainSourceSet.resources { srcDir("src/generated/resources") }

        extensions.configure<org.spongepowered.asm.gradle.plugins.MixinExtension>("mixin") {
            add(mainSourceSet, "mixins.bteterrarenderer.refmap.json")
            config("mixins.bteterrarenderer.json")
        }

        tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
            manifest.attributes(
                    "MixinConfigs" to "mixins.bteterrarenderer.json",
                    "FMLAT" to "accesstransformer.cfg",
                    "ForceLoadAsMod" to "true",
                    "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
                    "TweakOrder" to 0,
                    "Manifest-Version" to 1.0
            )
        }
    }

    tasks.withType<ProcessResources>() {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        val target = layout.buildDirectory.dir("resources/main").get().asFile

        val resourceTargets = listOf(
                "mcmod.info",
                "META-INF/mods.toml",
                "fabric.mod.json"
        )
        val replaceProperties = mapOf(
                "version" to             rootProject.property("mod_version"),
                "mcversion" to           (project.findProperty("minecraftVersion") ?: ""),
                "authors" to             rootProject.property("mod_authors"),
                "displayName" to         rootProject.property("mod_displayName"),
                "description" to         rootProject.property("mod_description"),
                "url" to                 rootProject.property("mod_url"),
                "sourceUrl" to           rootProject.property("mod_sourceUrl"),
                "discordUrl" to          rootProject.property("mod_discordUrl"),
                "credits" to             rootProject.property("mod_credits"),
                "license" to             rootProject.property("mod_license"),
                "fabricLoaderVersion" to rootProject.property("fabricLoaderVersion")
        )

        inputs.properties(replaceProperties)

        filesMatching(resourceTargets) { expand(replaceProperties) }

        copy {
            from(mainSourceSet.resources) {
                include(resourceTargets)
                expand(replaceProperties)
            }
            into(target)
        }

        doLast {
            if (subprojectType == SubprojectType.CORE) {
                fileTree(outputs.files.asPath) { include("**/*.lang") }.forEach { langFile: File ->
                    val r1 = Regex("#.*")
                    val r2 = Regex("""([^=]+)=(.+)""")
                    val content = langFile.readText(Charsets.UTF_8).split("\n")
                        .asSequence()
                        .map { it.replace(r1, "") }
                        .mapNotNull { r2.find(it) }
                        .map { it.groupValues[1] to it.groupValues[2] }
                        .map { (k, v) ->
                            """    "$k": "${v.replace("\"", "\\\\\"")}""""
                        }
                        .joinToString(",\n")

                    val r3 = Regex("""\.[^.]+$""")
                    val jsonPath = langFile.path.replaceFirst(r3, "") + ".json"
                    val jsonFile = file(jsonPath)

                    jsonFile.writeText("{\n$content\n}", Charsets.UTF_8)
                }
            }
            else if (subprojectType.isMod) {
                val logoFile = File(project(":core").projectDir, "src/main/resources/icon.png")
                val logoContent = logoFile.readBytes()

                val targetLogoFile = File(outputs.files.asPath, "icon.png")
                targetLogoFile.writeBytes(logoContent)
            }
        }
    }

    if (subprojectType.isMod) {
        project.tasks.register<Copy>("copyBuildResultToRoot") {
            group = "build"
            description = "Copies build result into root build directory"
            from("${project.projectDir}/build/libs")
            into("${rootProject.projectDir}/build/libs")
            dependsOn("build")
        }
        tasks.named("build").configure { finalizedBy("copyBuildResultToRoot") }

        project.tasks.register<Delete>("cleanModProjects") {
            group = "build"
            description = "Cleans mod projects"
            dependsOn("clean")
        }
    }
    else {
        tasks.named("test").configure { dependsOn(rootProject.tasks.named("gitSubmoduleUpdate")) }
        project.tasks.register("buildNonModProjects") {
            group = "build"
            description = "Builds non-mod projects.\nThis is because fabric requires dependency jars to be present before building."
            dependsOn("build")
        }
    }

    tasks.withType<Jar>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    version = "${rootProject.property("mod_version")}-${project.name}"
    group = rootProject.property("mod_group").toString()

    extensions.configure<BasePluginExtension> {
        archivesName = rootProject.property("mod_id").toString()
    }

    tasks.named<JavaCompile>("compileJava") {
        options.encoding = "UTF-8"
    }

    repositories {
        mavenCentral()
        maven("https://repo.spongepowered.org/maven/")
        maven("https://maven.daporkchop.net/")
        maven("https://repo.opencollab.dev/snapshot/")
        maven("https://jitpack.io/")
        maven("https://repo.elytradev.com/")
    }
}

extensions.configure<PublishingExtension> {
    publications {
        //noinspection GroovyAssignabilityCheck
        create<MavenPublication>("mavenJava") {
            groupId = "com.mndk"
            artifactId = "bteterrarenderer-core"
            version = rootProject.property("mod_version").toString()
            from(project(":core").components["java"])
        }
    }
}

tasks.register<Exec>("gitSubmoduleUpdate") {
    group = "other"
    description = "Updates submodules"

    commandLine("git", "submodule", "update", "--init")

    val stdout = ByteArrayOutputStream()
    standardOutput = stdout
    doLast {
        println("Submodule update command output: ")
        if (stdout.size() > 0) println(stdout.toString())
        else println("(none)")
    }
}

class ReplacePropertyContentTransformer : Transformer {

    @Input
    lateinit var replacements: Map<String, String>

    val pathMap = hashMapOf<String, String>()

    override fun canTransformResource(fileTreeElement: FileTreeElement): Boolean {
        return fileTreeElement.relativePath.pathString.endsWith(".properties")
    }

    override fun transform(context: TransformerContext) {
        val buffer = ByteArrayOutputStream()
        IOUtil.copy(context.`is`, buffer)
        context.`is`.close()
        var content = buffer.toString("UTF-8")

        this.replacements.forEach { (k, v) ->
            if (k !in content) return
            content = content.replace(k, v)
        }
        this.pathMap[context.path] = content
    }

    override fun hasTransformedResource(): Boolean = !this.pathMap.isEmpty()

    override fun modifyOutputStream(os: ZipOutputStream, b: Boolean) {
        val zipWriter = OutputStreamWriter(os, "UTF-8")
        this.pathMap.forEach { (path, content) ->
            val entry = ZipEntry(path)
            entry.time = TransformerContext.getEntryTimestamp(b, entry.time)
            os.putNextEntry(entry)
            IOUtil.copy(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)), zipWriter)
            zipWriter.flush()
            os.closeEntry()
        }
        this.pathMap.clear()
    }

    override fun getName(): String = "ReplacePropertyContentTransformer"
}

subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("fabric-loom")) {
            configure<LoomGradleExtensionAPI> {
                mods {
                    named("bteterrarenderer") {
                        sourceSet(rootProject.project(":core").extensions.getByType<JavaPluginExtension>().sourceSets["main"])
                        sourceSet(rootProject.project(":terraplusplus").extensions.getByType<JavaPluginExtension>().sourceSets["main"])
                        sourceSet(rootProject.project(":ogc3dtiles").extensions.getByType<JavaPluginExtension>().sourceSets["main"])
                        sourceSet(rootProject.project(":draco").extensions.getByType<JavaPluginExtension>().sourceSets["main"])
                    }
                }
            }
        }
    }
}