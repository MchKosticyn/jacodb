/*
 *  Copyright 2022 UnitTestBot contributors (utbot.org)
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jacodb.analysis.codegen

import mu.KotlinLogging
import org.jacodb.analysis.codegen.language.base.AnalysisVulnerabilityProvider
import org.jacodb.analysis.codegen.language.base.TargetLanguage
import org.jacodb.analysis.codegen.language.base.VulnerabilityInstance
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.Collections.min
import kotlin.IllegalStateException
import kotlin.io.path.notExists
import kotlin.io.path.useDirectoryEntries
import kotlin.random.Random

private val logger = KotlinLogging.logger { }

class AccessibilityCache(n: Int, private val graph: Map<Int, Set<Int>>) {
    private val used = Array(n) { 0 }
    private var currentWave = 0
    val badQuery = -1 to -1
    val badPath = listOf(-1)
    private var lastQuery = badQuery
    private var lastQueryPath = mutableListOf<Int>()

    private fun dfs(u: Int, target: Int): Boolean {
        used[u] = currentWave
        if (u == target) {
            lastQueryPath = mutableListOf(u)
            return true
        }

        for (v in graph.getOrDefault(u, emptySet())) {
            if (used[v] != currentWave && dfs(v, target)) {
                lastQueryPath.add(u)
                return true
            }
        }

        return false
    }

    fun isAccessible(u: Int, v: Int): Boolean {
        ++currentWave
        lastQuery = badQuery
        if (dfs(u, v)) {
            lastQueryPath.reverse()
            lastQuery = u to v
            return true
        }
        return false
    }


    fun getAccessPath(u: Int, v: Int): List<Int> {
        if (lastQuery == u to v || isAccessible(u, v))
            return lastQueryPath

        return badPath
    }
}

fun main(args: Array<String>) {
    assert(args.size in 5..6) {
        "vertices:Int edges:Int vulnerabilities:Int pathToProjectDir:String targetLanguage:String [clearTargetDir: Boolean]"
    }
    val n = args[0].toInt()
    val m = args[1].toInt()
    val k = args[2].toInt()

    assert(n in 2 until 1000) { "currently big graphs not supported just in case" }
    assert(m in 1 until 1000000) { "though we permit duplicated edges, do not overflow graph too much" }
    assert(k in 0 until min(listOf(255, n, m)))

    val projectPath = Paths.get(args[3]).normalize()

    assert(projectPath.notExists() || projectPath.useDirectoryEntries { it.none() }) { "Provide path to directory which either does not exists or empty" }

    val targetLanguageString = args[4]
    val targetLanguageService = ServiceLoader.load(TargetLanguage::class.java)
    val targetLanguage = targetLanguageService.single { it.javaClass.simpleName == targetLanguageString }

    val vulnerabilityProviderService = ServiceLoader.load(AnalysisVulnerabilityProvider::class.java)
    val vulnerabilityProviders = mutableListOf<AnalysisVulnerabilityProvider>()

    for (analysis in vulnerabilityProviderService) {
        if (analysis.isApplicable(targetLanguage)) {
            logger.info { "analysis used: ${analysis.javaClass.simpleName}" }
            vulnerabilityProviders.add(analysis)
        }
    }

    logger.info { "analyses summary: vertices - $n, edges - $m, analyses instances - $k" }

    val randomSeed = arrayOf(n, m, k).contentHashCode()
    val randomer = Random(randomSeed)

    logger.info { "debug seed: $randomSeed" }

    val fullClear = args.getOrElse(5) { "false" }.toBooleanStrict()

    targetLanguage.unzipTemplateProject(projectPath, fullClear)

    val graph = mutableMapOf<Int, MutableSet<Int>>()

    var i = 0
    while (i < m) {
        val u = randomer.nextInt(n)
        val v = randomer.nextInt(n)

        if (u != v) {
            // TODO loops v->v?
            graph.getOrPut(u) { mutableSetOf() }.add(v)
            i++
        }
    }

    val accessibilityCache = AccessibilityCache(n, graph)
    val codeRepresentation = CodeRepresentation(targetLanguage)
    val generatedVulnerabilitiesList = mutableListOf<VulnerabilityInstance>()

    i = 0
    while (i < k) {
        val u = randomer.nextInt(n)
        val v = randomer.nextInt(n)
        val vulnerabilityIndex = randomer.nextInt(vulnerabilityProviders.size)
        val vulnerabilityProvider = vulnerabilityProviders[vulnerabilityIndex]

        if (accessibilityCache.isAccessible(u, v)) {
            val path = accessibilityCache.getAccessPath(u, v)
            val instance = vulnerabilityProvider.provideInstance(codeRepresentation) {
                createSource(u)
                for (j in 0 until path.lastIndex) {
                    val startOfEdge = path[j]
                    val endOfEdge = path[j + 1]
                    mutateVulnerability(startOfEdge, endOfEdge)
                    transitVulnerability(startOfEdge, endOfEdge)
                }
                createSink(v)
            }
            generatedVulnerabilitiesList.add(instance)
            i++
        }
    }

    codeRepresentation.dumpTo(projectPath)
    gradlewAssemble(targetLanguage, projectPath)
}

private fun gradlewAssemble(targetLanguage: TargetLanguage, projectPath: Path) {
    checkJava()
    val zipName = targetLanguage.projectZipInResourcesName()
    val workingDir = File(projectPath.toFile(), zipName.substring(0, zipName.length - 4))
    if (!isWindows) {
        chmodGradlew(workingDir)
    }
    val gradlewScript = "./gradlew" + if (isWindows) ".bat" else ""
    runCmd(
        cmd = listOf(gradlewScript, "assemble"),
        errorMessage = "problems with assembling",
        filePrefix = "gradlew",
        logPrefix = "gradle building: ",
        workingDir = workingDir
    )
}

private fun runCmd(
    cmd: List<String>,
    errorMessage: String,
    filePrefix: String,
    logPrefix: String,
    shouldCheckJava: Boolean = false,
    workingDir: File?
): List<String> {
    try {
        val cmdBuilder = ProcessBuilder()
        var dirToCreateFiles = ""
        if (workingDir != null) {
            cmdBuilder.directory(workingDir)
            dirToCreateFiles = workingDir.absolutePath + File.separatorChar
        }
        cmdBuilder.command(cmd)
        val errorFileName = dirToCreateFiles + filePrefix + "Errors"
        val outputFileName = dirToCreateFiles + filePrefix + "Output"
        val errorFile = File(errorFileName)
        val outputFile = File(outputFileName)
        val cmdProcess = cmdBuilder.redirectError(errorFile).redirectOutput(outputFile).start()
        cmdProcess.waitFor()
        val errorLines = Files.readAllLines(errorFile.toPath()).map { s -> s + System.lineSeparator() }
        val outputLines = Files.readAllLines(outputFile.toPath()).map { s -> s + System.lineSeparator() }
        errorFile.delete()
        outputFile.delete()
        if (errorLines.isNotEmpty() && !shouldCheckJava) {
            logger.error { "$errorMessage: $errorLines" }
            throw IllegalStateException(errorMessage)
        }
        val loggingInfo =
            if (!shouldCheckJava) outputLines else errorLines
        logger.info {
            logPrefix + loggingInfo
        }
        return loggingInfo
    } catch (e: IOException) {
        throw IllegalStateException(errorMessage)
    }
}

private fun checkJava() {
    val canonicalJavaName = "java" + if (isWindows) ".exe" else ""
    val javaPathToCheck: String
    val javaHome = System.getenv("JAVA_HOME")
    if (javaHome.isNullOrEmpty()) {
        if (System.getenv("PATH").isNullOrEmpty()) {
            logger.error { "No PATH variable found" }
            throw IllegalStateException("No PATH variable found")
        }
        val foundJavaExecutables = System.getenv("PATH").split(File.pathSeparator).filter { s: String ->
            File(s + File.separator + canonicalJavaName).exists()
        }
        if (foundJavaExecutables.isEmpty()) {
            logger.error { "no java found in environment" }
            throw IllegalStateException("no java found in environment")
        }
        javaPathToCheck = foundJavaExecutables[0]
    } else {
        javaPathToCheck = javaHome + File.separator + "bin" + File.separator + canonicalJavaName
    }
    val javaResult = runCmd(
        cmd = listOf(javaPathToCheck.split(File.separator).joinToString(File.separator) { s ->
            if (s.contains(" ")) "\"" + s + "\"" else s
        } + File.separator + canonicalJavaName, "-version"),
        errorMessage = "problems with getting java version",
        logPrefix = "getting java version: ",
        workingDir = null,
        shouldCheckJava = true,
        filePrefix = "java"
    )
    if (javaResult.isEmpty()) {
        logger.error { "no java found in environment" }
        throw IllegalStateException("no java found in environment")
    }
    val javaDescription = javaResult[0]
    var javaVersion = javaDescription.split(" ")[2]
    javaVersion = javaVersion.substring(1, javaVersion.length - 1)
    if (javaVersion < "1.8") {
        logger.error { "java version must being 8 or higher. current env java: $javaVersion" }
        throw IllegalStateException("java version must being 8 or higher. current env java: $javaVersion")
    }
}

private fun chmodGradlew(file: File) {
    runCmd(
        cmd = listOf("chmod", "+x", "gradlew"),
        errorMessage = "problems with chmod",
        filePrefix = "chmod",
        logPrefix = "chmod message: ",
        workingDir = file
    )
}