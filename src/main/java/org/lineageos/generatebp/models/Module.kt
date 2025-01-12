/*
 * SPDX-FileCopyrightText: 2023-2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.generatebp.models

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.lineageos.generatebp.utils.Logger
import kotlin.reflect.safeCast

/**
 * A Gradle module.
 * @param group The group name
 * @param name The name of the module
 * @param version The version of the module, set to "any" by default. This field is ignored for
 *                comparison and it's only used for [aospModulePath]
 * @param dependencies The dependencies of the module
 * @param artifact The artifact of this module
 */
data class Module(
    val group: String,
    val name: String,
    val version: String = VERSION_ANY,
    val dependencies: Set<Module> = setOf(),
    val artifact: Artifact? = null,
    val hasJarParentedArtifacts: Boolean = false,
) : Comparable<Module> {
    override fun equals(other: Any?) = Module::class.safeCast(other)?.let {
        compareTo(it) == 0
    } ?: false

    override fun hashCode(): Int {
        var result = group.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }

    override fun compareTo(other: Module) = compareValuesBy(
        this, other,
        { it.group },
        { it.name },
    )

    val gradleName = "$group:$name:$version"

    val aospModulePath = "${group.replace(".", "/")}/${name}/${version}"

    companion object {
        private const val VERSION_ANY = "any"

        fun fromModuleVersionIdentifier(it: ModuleVersionIdentifier) =
            Module(it.group, it.name, it.version)

        fun fromResolvedDependency(
            it: ResolvedDependency,
            targetSdk: Int,
            skipDependencies: Boolean = false,
        ): Module = Module(
            it.moduleGroup,
            it.moduleName,
            it.moduleVersion,
            it.takeUnless { skipDependencies }?.children?.map {
                fromResolvedDependency(it, targetSdk, true)
            }?.toSet() ?: setOf(),
            it.moduleArtifacts.also {
                if (it.size > 1) {
                    Logger.debug("Multiple artifacts found, using first one: $it")
                }
            }.firstOrNull()?.let { resolvedArtifact ->
                Artifact.fromResolvedArtifact(resolvedArtifact, targetSdk)
            },
            it.parents.all { parents ->
                parents.moduleArtifacts.all { it.extension == "jar" }
            } && it.moduleArtifacts.any {
                it.extension == "aar"
            },
        )

        fun fromProjectDependency(
            it: ProjectDependency,
            targetSdk: Int,
        ): Module {
            val dependencyProject = it.dependencyProject

            return Module(
                dependencyProject.group.toString(),
                dependencyProject.name,
                dependencyProject.version.toString(),
                dependencyProject.configurations.flatMap { configuration ->
                    configuration.allDependencies
                }.mapNotNull { dependency ->
                    dependency as? ProjectDependency
                }.map {
                    fromProjectDependency(it, targetSdk)
                }.toSet(),
                dependencyProject.configurations.flatMap { configuration ->
                    configuration.allDependencies
                }.firstNotNullOfOrNull { dependency ->
                    dependency as? ResolvedArtifact
                }?.let { resolvedArtifact ->
                    Artifact.fromResolvedArtifact(resolvedArtifact, targetSdk)
                },
                dependencyProject.configurations.flatMap { configuration ->
                    configuration.allDependencies
                }.mapNotNull { dependency ->
                    dependency as? ResolvedDependency
                }.map {
                    it.parents.all { parents ->
                        parents.moduleArtifacts.all { it.extension == "jar" }
                    } && it.moduleArtifacts.any {
                        it.extension == "aar"
                    }
                }.any { it },
            )
        }
    }
}
