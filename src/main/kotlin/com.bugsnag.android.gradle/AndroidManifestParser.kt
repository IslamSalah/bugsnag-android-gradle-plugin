package com.bugsnag.android.gradle

import groovy.util.Node
import groovy.util.NodeList
import groovy.util.XmlNodePrinter
import groovy.util.XmlParser
import groovy.xml.Namespace
import org.gradle.api.logging.Logger
import org.xml.sax.SAXException
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import javax.xml.parsers.ParserConfigurationException

class AndroidManifestParser {

    private val namespace = Namespace("http://schemas.android.com/apk/res/android", "android")

    @Throws(ParserConfigurationException::class, SAXException::class, IOException::class)
    fun readManifest(manifestPath: File, logger: Logger): AndroidManifestInfo {
        logger.debug("Reading manifest at: \${manifestPath}")
        val root = XmlParser().parse(manifestPath)
        val application = (root[TAG_APPLICATION] as NodeList)[0] as Node
        val metadataTags = findMetadataTags(application)

        // Get the Bugsnag API key
        val apiKey = getManifestMetaData(metadataTags, TAG_API_KEY)
        if (apiKey == null) {
            logger.warn("Could not find apiKey in '$TAG_API_KEY' " +
                "<meta-data> tag in your AndroidManifest.xml")
        }

        // Get the build version
        val versionCode = getVersionCode(metadataTags, root)
        if (versionCode == null) {
            logger.warn("Could not find 'android:versionCode' value in your AndroidManifest.xml")
        }

        // Uniquely identify the build so that we can identify the proguard file.
        val buildUUID = getManifestMetaData(metadataTags, TAG_BUILD_UUID)
        if (buildUUID == null) {
            logger.warn("Could not find '$TAG_BUILD_UUID'" +
                " <meta-data> tag in your AndroidManifest.xml")
        }

        // Get the version name
        val versionName = getVersionName(metadataTags, root)
        if (versionName == null) {
            logger.warn("Could not find 'android:versionName' value in your AndroidManifest.xml")
        }
        if (apiKey == null || versionCode == null || buildUUID == null || versionName == null) {
            throw IllegalStateException("Missing apiKey/versionCode/buildUuid/versionName, required to upload to bugsnag.")
        }
        return AndroidManifestInfo(apiKey, versionCode, buildUUID, versionName)
    }

    @Throws(ParserConfigurationException::class, SAXException::class, IOException::class)
    fun writeBuildUuid(manifestPath: File, buildUuid: String) {
        val root = XmlParser().parse(manifestPath)
        val application = (root[TAG_APPLICATION] as NodeList)[0] as Node
        val metadataTags = findMetadataTags(application)

        // If the current manifest does not contain the build ID then try the next manifest in the list (if any)
        if (!hasBuildUuid(metadataTags)) {
            // Add the new BUILD_UUID_TAG element
            application.appendNode(TAG_META_DATA, hashMapOf(
                namespace.get(ATTR_NAME) to TAG_BUILD_UUID,
                namespace.get(ATTR_VALUE) to buildUuid
            ))

            // Write the manifest file
            FileWriter(manifestPath).use {
                val printer = XmlNodePrinter(PrintWriter(it))
                printer.isPreserveWhitespace = true
                printer.print(root)
            }
        }
    }

    private fun findMetadataTags(application: Node): List<Node> {
        return application.children()
            .asSequence()
            .filterIsInstance<Node>()
            .filter { TAG_META_DATA == it.name() }
            .toList()
    }

    private fun hasBuildUuid(metadataTags: List<Node>): Boolean {
        return getManifestMetaData(metadataTags, TAG_BUILD_UUID) != null
    }

    private fun getManifestMetaData(metadataTags: List<Node>, key: String): String? {
        val node = metadataTags.find {
            val name = it.attribute(namespace.get(ATTR_NAME))
            key == name
        }
        return node?.attribute(namespace.get(ATTR_VALUE)) as String?
    }

    private fun getVersionName(metaDataTags: List<Node>, xml: Node): String? {
        val versionName = getManifestMetaData(metaDataTags, TAG_APP_VERSION)
        return versionName ?: xml.attribute(namespace.get(ATTR_VERSION_NAME)) as String?
    }

    fun getVersionCode(metaDataTags: List<Node>, xml: Node): String? {
        val versionCode = getManifestMetaData(metaDataTags, TAG_VERSION_CODE)
        return versionCode ?: xml.attribute(namespace.get(ATTR_VERSION_CODE)) as String?
    }

    companion object {
        private const val TAG_APPLICATION = "application"
        private const val TAG_META_DATA = "meta-data"
        private const val TAG_API_KEY = "com.bugsnag.android.API_KEY"
        private const val TAG_BUILD_UUID = "com.bugsnag.android.BUILD_UUID"
        private const val TAG_VERSION_CODE = "com.bugsnag.android.VERSION_CODE"
        private const val TAG_APP_VERSION = "com.bugsnag.android.APP_VERSION"
        private const val ATTR_NAME = "name"
        private const val ATTR_VALUE = "value"
        private const val ATTR_VERSION_CODE = "versionCode"
        private const val ATTR_VERSION_NAME = "versionName"
    }
}
