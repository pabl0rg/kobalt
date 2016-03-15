package com.beust.kobalt.app

import com.beust.kobalt.Args
import com.beust.kobalt.Constants
import com.beust.kobalt.Plugins
import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.api.KobaltContext
import com.beust.kobalt.api.PluginProperties
import com.beust.kobalt.api.Project
import com.beust.kobalt.internal.KobaltSettings
import com.beust.kobalt.internal.PluginInfo
import com.beust.kobalt.internal.build.BuildFile
import com.beust.kobalt.internal.build.VersionFile
import com.beust.kobalt.maven.DependencyManager
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltExecutors
import com.beust.kobalt.misc.log
import com.beust.kobalt.plugin.kotlin.kotlinCompilePrivate
import com.google.inject.assistedinject.Assisted
import java.io.File
import java.net.URL
import java.nio.file.Paths
import javax.inject.Inject

/**
 * Manage the compilation of Build.kt. There are two passes for this processing:
 * 1) Extract the repos() and plugins() statements in a separate .kt and compile it into preBuildScript.jar.
 * 2) Actually build the whole Build.kt file after adding to the classpath whatever phase 1 found (plugins, repos)
 */
public class BuildFileCompiler @Inject constructor(@Assisted("buildFiles") val buildFiles: List<BuildFile>,
        @Assisted val pluginInfo: PluginInfo, val files: KFiles, val plugins: Plugins,
        val dependencyManager: DependencyManager, val pluginProperties: PluginProperties,
        val executors: KobaltExecutors, val buildScriptUtil: BuildScriptUtil, val settings: KobaltSettings) {

    interface IFactory {
        fun create(@Assisted("buildFiles") buildFiles: List<BuildFile>, pluginInfo: PluginInfo) : BuildFileCompiler
    }

    private val SCRIPT_JAR = "buildScript.jar"

    fun compileBuildFiles(args: Args): FindProjectResult {
        //
        // Create the KobaltContext
        //
        val context = KobaltContext(args)
        context.pluginInfo = pluginInfo
        context.pluginProperties = pluginProperties
        context.dependencyManager = dependencyManager
        context.executors = executors
        context.settings = settings
        Kobalt.context = context

        //
        // Find all the projects in the build file, possibly compiling them
        //
        val projectResult = findProjects(context)
        if (projectResult.taskResult.success) {
            plugins.applyPlugins(context, projectResult.projects)
        }

        return projectResult
    }

    val parsedBuildFiles = arrayListOf<ParsedBuildFile>()

    class FindProjectResult(val projects: List<Project>, val taskResult: TaskResult)

    private fun findProjects(context: KobaltContext): FindProjectResult {
        var errorTaskResult: TaskResult? = null
        val projects = arrayListOf<Project>()
        buildFiles.forEach { buildFile ->
            val parsedBuildFile = parseBuildFile(context, buildFile)
            parsedBuildFiles.add(parsedBuildFile)
            val pluginUrls = parsedBuildFile.pluginUrls
            val buildScriptJarFile = File(KFiles.findBuildScriptLocation(buildFile, SCRIPT_JAR))

            // If the script jar files were generated by a different version, wipe them in case the API
            // changed in-between
            buildScriptJarFile.parentFile.let { dir ->
                if (! VersionFile.isSameVersionFile(dir)) {
                    log(1, "Detected new installation, wiping $dir")
                    dir.listFiles().map { it.delete() }
                }
            }

            // Write the modified Build.kt (e.g. maybe profiles were applied) to a temporary file,
            // compile it, jar it in buildScript.jar and run it
            val modifiedBuildFile = KFiles.createTempFile(".kt")
            KFiles.saveFile(modifiedBuildFile, parsedBuildFile.buildScriptCode)
            val taskResult = maybeCompileBuildFile(context, BuildFile(Paths.get(modifiedBuildFile.path),
                    "Modified ${Constants.BUILD_FILE_NAME}", buildFile.realPath),
                    buildScriptJarFile, pluginUrls)
            if (taskResult.success) {
                projects.addAll(buildScriptUtil.runBuildScriptJarFile(buildScriptJarFile, pluginUrls, context))
            } else {
                if (errorTaskResult == null) {
                    errorTaskResult = taskResult
                }
            }
        }
        return FindProjectResult(projects, if (errorTaskResult != null) errorTaskResult!! else TaskResult())
    }

    private fun maybeCompileBuildFile(context: KobaltContext, buildFile: BuildFile, buildScriptJarFile: File,
            pluginUrls: List<URL>) : TaskResult {
        log(2, "Running build file ${buildFile.name} jar: $buildScriptJarFile")

        if (buildScriptUtil.isUpToDate(buildFile, buildScriptJarFile)) {
            log(2, "Build file is up to date")
            return TaskResult()
        } else {
            log(2, "Need to recompile ${buildFile.name}")

            buildScriptJarFile.delete()
            val result = kotlinCompilePrivate {
                classpath(files.kobaltJar)
                classpath(pluginUrls.map { it.file })
                sourceFiles(listOf(buildFile.path.toFile().absolutePath))
                output = buildScriptJarFile
            }.compile(context = context)

            return result
        }
    }

    /**
     * Generate the script file with only the plugins()/repos() directives and run it. Then return
     * - the source code for the modified Build.kt (after profiles are applied)
     * - the URL's of all the plug-ins that were found.
     */
    private fun parseBuildFile(context: KobaltContext, buildFile: BuildFile) =
            ParsedBuildFile(buildFile, context, buildScriptUtil, dependencyManager, files)
}
