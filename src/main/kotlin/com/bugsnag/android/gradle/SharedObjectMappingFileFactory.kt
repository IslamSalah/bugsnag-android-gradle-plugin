package com.bugsnag.android.gradle

import com.android.build.gradle.AppExtension
import okio.buffer
import okio.gzip
import okio.sink
import okio.source
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Project
import java.io.File
import java.io.InputStream
import java.io.Reader

/**
 * Generates a mapping file for the supplied shared object file.
 *
 * Currently this only supports NDK SO mapping files but in future this will support
 * other platforms which require different SO mapping support.
 */
internal object SharedObjectMappingFileFactory {

    internal const val SO_MAPPING_DIR = "intermediates/bugsnag/soMappings"

    internal data class Params(
        val sharedObject: File,
        val abi: Abi,
        val objDumpPaths: Map<String, String>,
        val outputDirectory: File
    )

    /**
     * Uses objdump to create a symbols file for the given shared object file.
     *
     * @param project the gradle project
     * @param params the parameters required to generate a SO mapping file
     * @return the output file location, or null on error
     */
    fun generateSoMappingFile(project: Project, params: Params): File? {
        // Get the path the version of objdump to use to get symbols
        val arch = params.abi.abiName
        val objDumpPath = getObjDumpExecutable(project, params.objDumpPaths, arch)
        val logger = project.logger
        if (objDumpPath != null) {
            val outReader: Reader? = null
            try {
                val rootDir = params.outputDirectory
                val archDir = File(rootDir, arch)
                archDir.mkdir()

                val outputName = params.sharedObject.name
                val outputFile = File(archDir, "$outputName.gz")
                val errorOutputFile = File(archDir, "$outputName.error.txt")
                logger.info("Bugsnag: Creating symbol file for $outputName at $outputFile")

                // Call objdump, redirecting output to the output file
                val builder = ProcessBuilder(objDumpPath.toString(),
                    "--dwarf=info", "--dwarf=rawline", params.sharedObject.toString())
                builder.redirectError(errorOutputFile)
                val process = builder.start()

                // Output the file to a zip
                val stdout = process.inputStream
                outputZipFile(stdout, outputFile)
                return if (process.waitFor() == 0) {
                    outputFile
                } else {
                    logger.error("Bugsnag: failed to generate symbols for $arch " +
                        "see $errorOutputFile for more details")
                    null
                }
            } catch (e: Exception) {
                logger.error("Bugsnag: failed to generate symbols for $arch ${e.message}", e)
            } finally {
                outReader?.close()
            }
        } else {
            logger.error("Bugsnag: Unable to upload NDK symbols: Could not find objdump location for $arch")
        }
        return null
    }

    /**
     * Gets the path to the objdump executable to use to get symbols from a shared object
     * @param arch The arch of the shared object
     * @return The objdump executable, or null if not found
     */
    private fun getObjDumpExecutable(project: Project, objDumpPaths: Map<String, String>, arch: String): File? {
        try {
            val override = getObjDumpOverride(objDumpPaths, arch)
            val objDumpFile: File
            objDumpFile = override?.let { File(it) } ?: findObjDump(project, arch)
            check((objDumpFile.exists() && objDumpFile.canExecute())) {
                "Failed to find executable objdump at $objDumpFile"
            }
            return objDumpFile
        } catch (ex: Throwable) {
            project.logger.error("Bugsnag: Error attempting to calculate objdump location: " + ex.message)
        }
        return null
    }

    private fun getObjDumpOverride(objDumpPaths: Map<String, String>, arch: String): String? {
        return objDumpPaths[arch]
    }

    /**
     * Outputs the contents of stdout into the gzip file output file
     *
     * @param stdout The input stream
     * @param outputFile The output file
     */
    private fun outputZipFile(stdout: InputStream, outputFile: File) {
        stdout.source().use { source ->
            outputFile.sink().gzip().buffer().use { gzipSink ->
                gzipSink.writeAll(source)
            }
        }
    }

    private fun findObjDump(project: Project, arch: String): File {
        val abi = Abi.findByName(arch)
        val android = project.extensions.getByType(AppExtension::class.java)
        val ndkDir = android.ndkDirectory.absolutePath
        val osName = calculateOsName()
        checkNotNull(abi) { "Failed to find ABI for $arch" }
        checkNotNull(osName) { "Failed to calculate OS name" }
        return calculateObjDumpLocation(ndkDir, abi, osName)
    }

    @JvmStatic
    fun calculateObjDumpLocation(ndkDir: String?, abi: Abi, osName: String): File {
        val executable = if (osName.startsWith("windows")) "objdump.exe" else "objdump"
        return File("$ndkDir/toolchains/${abi.toolchainPrefix}-4.9/prebuilt/" +
            "$osName/bin/${abi.objdumpPrefix}-$executable")
    }

    private fun calculateOsName(): String? {
        return when {
            Os.isFamily(Os.FAMILY_MAC) -> "darwin-x86_64"
            Os.isFamily(Os.FAMILY_UNIX) -> "linux-x86_64"
            Os.isFamily(Os.FAMILY_WINDOWS) -> {
                if ("x86" == System.getProperty("os.arch")) "windows" else "windows-x86_64"
            }
            else -> null
        }
    }
}