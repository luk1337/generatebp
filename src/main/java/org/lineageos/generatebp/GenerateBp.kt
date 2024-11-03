/*
 * SPDX-FileCopyrightText: 2023-2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.generatebp

import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.kotlin.dsl.get
import org.lineageos.generatebp.ext.*
import org.lineageos.generatebp.models.Artifact
import org.lineageos.generatebp.models.License
import org.lineageos.generatebp.models.Module
import org.lineageos.generatebp.utils.ReuseUtils
import java.io.File

internal class GenerateBp(
    private val project: Project,
    private val targetSdk: Int,
    private val isAvailableInAOSP: (module: Module) -> Boolean,
    private val libsBase: File = File("${project.projectDir.absolutePath}/libs"),
) {
    private val configuration = project.configurations["releaseRuntimeClasspath"]
    private val resolvedConfiguration = configuration.resolvedConfiguration

    private val isKotlinBomDependency = { dependency: ResolvedDependency ->
        dependency.moduleGroup == "org.jetbrains.kotlin" && dependency.moduleName == "kotlin-bom"
    }
    private val isValidProjectDependency = { dependency: ResolvedDependency ->
        !isKotlinBomDependency(dependency)
    }

    private val projectDependencies =
        resolvedConfiguration.firstLevelModuleDependencies.filter(isValidProjectDependency).map {
            Module.fromResolvedDependency(it, targetSdk)
        }

    private val allDependencies = resolvedConfiguration.firstLevelModuleDependencies.asSequence().map {
        it.recursiveDependencies
    }.flatten().toSet().map {
        Module.fromResolvedDependency(it, targetSdk)
    }.toSortedSet()

    private val jarDependenciesWithAARs = allDependencies.filter {
        it.hasJarParentedArtifacts
    }

    private val projectDependenciesWithAARs =
        (projectDependencies + jarDependenciesWithAARs).toSortedSet()

    private val libsAndroidBpHeader = buildString {
        append("//\n")
        append(
            ReuseUtils.generateReuseCopyrightContent(
                License.APACHE_2_0,
                listOf("The LineageOS Project"),
                initialYear = null,
                addNewlineBetweenCopyrightAndLicense = false,
                addEndingNewline = false,
                addCurrentYear = false,
            ).prependIndent("// ")
        )
        append("\n")
        append("//\n")
        append("\n")
        append("// DO NOT EDIT THIS FILE MANUALLY\n")
    }

    operator fun invoke() {
        // Delete old libs artifacts
        libsBase.deleteRecursively()

        // Update app/Android.bp
        File("${project.projectDir.absolutePath}/Android.bp").let { file ->
            val dependenciesString = buildString {
                append("\n")
                append(SHARED_LIBS_HEADER.indentWithSpaces(8))
                append("\n")
                append(
                    projectDependenciesWithAARs.map {
                        "\"${it.aospModuleName}\","
                    }.indentWithSpaces(8).joinToString("\n")
                )
                append("\n${spaces(4)}")
            }

            file.writeText(
                file.readText().replace(
                    // Replace existing dependencies with newly generated ones
                    "static_libs: \\[.*?]".toRegex(RegexOption.DOT_MATCHES_ALL),
                    "static_libs: [%s]".format(dependenciesString)
                ).replace(
                    // Replace existing sdk_version with one from targetSdk
                    "sdk_version: \"\\d+\"".toRegex(),
                    "sdk_version: \"${targetSdk}\""
                )
            )
        }

        // Update app/libs
        allDependencies.forEach {
            // Skip modules that are available in AOSP
            if (isAvailableInAOSP(it)) {
                return@forEach
            }

            // Create dir
            libsBase.mkdirs()

            it.artifact?.let { artifact ->
                // Get file path
                val dirPath = "${libsBase}/${it.aospModulePath}"
                val filePath = "${dirPath}/${artifact.file.name}"

                // Copy artifact to app/libs
                artifact.file.copyTo(File(filePath))

                // Write license file
                artifact.writeCopyrightFileForFile(filePath)

                // Extract AndroidManifest.xml for AARs
                if (artifact.file.extension == "aar") {
                    project.copy {
                        from(project.zipTree(filePath).matching {
                            include("/AndroidManifest.xml")
                        }.singleFile)
                        into(dirPath)
                    }

                    // Write license file
                    artifact.writeCopyrightFileForFile("${dirPath}/AndroidManifest.xml")
                }
            }

            // Write Android.bp
            File("$libsBase/Android.bp").let { file ->
                // Add autogenerated header if file is empty
                if (file.length() == 0L) {
                    file.writeText(libsAndroidBpHeader)
                }

                it.artifact?.also { artifact ->
                    when (artifact.fileType) {
                        Artifact.FileType.AAR -> {
                            file.appendText(
                                """

                                android_library_import {
                                    name: "${it.aospModuleName}-nodeps",
                                    aars: ["${it.aospModulePath}/${artifact.file.name}"],
                                    sdk_version: "${artifact.targetSdkVersion}",
                                    min_sdk_version: "${artifact.minSdkVersion}",
                                    apex_available: [
                                        "//apex_available:platform",
                                        "//apex_available:anyapex",
                                    ],
                                    static_libs: [%s],${when (artifact.hasJNIs) {
                                            true -> """
                                    extract_jni: true,"""
                                            false -> ""
                                        }
                                    }
                                }
    
                                android_library {
                                    name: "${it.aospModuleName}",
                                    sdk_version: "${artifact.targetSdkVersion}",
                                    min_sdk_version: "${artifact.minSdkVersion}",
                                    apex_available: [
                                        "//apex_available:platform",
                                        "//apex_available:anyapex",
                                    ],
                                    manifest: "${it.aospModulePath}/AndroidManifest.xml",
                                    static_libs: [%s],
                                    java_version: "1.7",
                                }

                                """.trimIndent().format(
                                    it.formatDependencies(false),
                                    it.formatDependencies(true)
                                )
                            )
                        }

                        Artifact.FileType.JAR -> {
                            file.appendText(
                                """
    
                                java_import {
                                    name: "${it.aospModuleName}-nodeps",
                                    jars: ["${it.aospModulePath}/${artifact.file.name}"],
                                    sdk_version: "${artifact.targetSdkVersion}",
                                    min_sdk_version: "${artifact.minSdkVersion}",
                                    apex_available: [
                                        "//apex_available:platform",
                                        "//apex_available:anyapex",
                                    ],
                                }
    
                                java_library_static {
                                    name: "${it.aospModuleName}",
                                    sdk_version: "${artifact.targetSdkVersion}",
                                    min_sdk_version: "${artifact.minSdkVersion}",
                                    apex_available: [
                                        "//apex_available:platform",
                                        "//apex_available:anyapex",
                                    ],
                                    static_libs: [%s],
                                    java_version: "1.7",
                                }

                                """.trimIndent().format(
                                    it.formatDependencies(true)
                                )
                            )
                        }
                    }
                } ?: file.appendText(
                    """

                    java_library_static {
                        name: "${it.aospModuleName}",
                        sdk_version: "$targetSdk",
                        min_sdk_version: "${Artifact.DEFAULT_MIN_SDK_VERSION}",
                        apex_available: [
                            "//apex_available:platform",
                            "//apex_available:anyapex",
                        ],
                        static_libs: [%s],
                        java_version: "1.7",
                    }

                    """.trimIndent().format(
                        it.formatDependencies(false)
                    )
                )
            }
        }
    }

    private val Module.aospModuleName
        get() = if (isAvailableInAOSP(this)) {
            moduleNameAOSP("${group}:${name}")
        } else {
            "${project.rootProject.name}_${group}_${name}"
        }

    private val isKotlinBom = { module: Module ->
        module.group == "org.jetbrains.kotlin" && module.name == "kotlin-bom"
    }
    private val isKotlinStdlibCommon = { module: Module ->
        module.group == "org.jetbrains.kotlin" && module.name == "kotlin-stdlib-common"
    }
    private val isValidAospModule = { module: Module ->
        !isKotlinBom(module) && !isKotlinStdlibCommon(module)
    }

    private fun Module.formatDependencies(addNoDependencies: Boolean): String {
        val aospDependencies = dependencies.asSequence().filter(isValidAospModule).distinct().map {
            it.aospModuleName
        }.sorted().toMutableList()

        if (addNoDependencies) {
            // Add -nodeps dependency for android_library/java_library_static
            aospDependencies.add(0, "${aospModuleName}-nodeps")
        }

        return aospDependencies.map {
            "\"${it}\","
        }.indentWithSpaces(8).joinToString("") {
            "\n$it"
        } + "\n${spaces(4)}"
    }

    companion object {
        private const val SHARED_LIBS_HEADER = "// DO NOT EDIT THIS SECTION MANUALLY"

        private fun moduleNameAOSP(moduleName: String) = when (moduleName) {
            "androidx.annotation:annotation-jvm" -> "androidx.annotation_annotation"
            "androidx.constraintlayout:constraintlayout" -> "androidx-constraintlayout_constraintlayout"
            "androidx.test.espresso:espresso-accessibility" -> "androidx.test.espresso.accessibility"
            "androidx.test.espresso:espresso-contrib" -> "androidx.test.espresso.contrib"
            "androidx.test.espresso:espresso-core" -> "androidx.test.espresso.core"
            "androidx.test.espresso:espresso-idling-resource" -> "androidx.test.espresso.idling-resource"
            "androidx.test.espresso:espresso-intents" -> "androidx.test.espresso.intents"
            "androidx.test.espresso:espresso-web" -> "androidx.test.espresso.web"
            "com.github.bumptech.glide:glide" -> "glide"
            "com.google.auto.value:auto-value-annotations" -> "auto_value_annotations"
            "com.google.code.findbugs:jsr305" -> "jsr305"
            "com.google.code.gson:gson" -> "gson"
            "com.google.errorprone:error_prone_annotations" -> "error_prone_annotations"
            "com.google.errorprone:error_prone_core" -> "error_prone_core"
            "com.google.dagger:dagger" -> "dagger2"
            "com.google.dagger:hilt-android" -> "hilt_android"
            "com.google.dagger:hilt-core" -> "hilt_core"
            "com.google.guava:guava" -> "guava"
            "com.google.guava:listenablefuture" -> "guava"
            "com.squareup.okhttp3:okhttp" -> "okhttp-norepackage"
            "com.squareup.okio:okio" -> "okio-lib"
            "javax.inject:javax.inject" -> "jsr330"
            "org.bouncycastle:bcpkix-jdk15on" -> "bouncycastle-bcpkix-unbundled"
            "org.bouncycastle:bcpkix-jdk18on" -> "bouncycastle-bcpkix-unbundled"
            "org.bouncycastle:bcprov-jdk15on" -> "bouncycastle-unbundled"
            "org.bouncycastle:bcprov-jdk18on" -> "bouncycastle-unbundled"
            "org.jetbrains.kotlin:kotlin-stdlib" -> "kotlin-stdlib"
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7" -> "kotlin-stdlib-jdk7"
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8" -> "kotlin-stdlib-jdk8"
            "org.jetbrains.kotlin:kotlin-stdlib-jre7" -> "kotlin-stdlib-jdk7"
            "org.jetbrains.kotlin:kotlin-stdlib-jre8" -> "kotlin-stdlib-jdk8"
            "org.jetbrains.kotlinx:kotlinx-coroutines-android" -> "kotlinx-coroutines-android"
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" -> "kotlinx-coroutines-core"
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm" -> "kotlinx-coroutines-core-jvm"
            "org.jetbrains.kotlinx:kotlinx-coroutines-guava" -> "kotlinx_coroutines_guava"
            "org.jetbrains.kotlinx:kotlinx-coroutines-reactive" -> "kotlinx_coroutines_reactive"
            "org.jetbrains.kotlinx:kotlinx-coroutines-rx2" -> "kotlinx_coroutines_rx2"
            "org.jetbrains.kotlinx:kotlinx-serialization-core" -> "kotlinx_serialization_core"
            "org.jetbrains.kotlinx:kotlinx-serialization-json" -> "kotlinx_serialization_json"
            else -> moduleName.replace(":", "_")
        }

        private fun Artifact.writeCopyrightFileForFile(file: String) {
            reuseCopyrightFileContent.takeIf { it.isNotEmpty() }?.let {
                File("$file.license").writeText(it)
            }
        }
    }
}
