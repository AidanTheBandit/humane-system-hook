package com.penumbraos.server

import android.content.Context
import android.util.Log
import java.io.File

object BootstrapConfig {

    private const val TAG = "PenumbraServer"
    private const val BOOTSTRAP_ASSET = "bootstrap-config.toml"
    private const val CONFIG_FILE_NAME = "config.toml"
    private const val DATA_DIR_NAME = "server-data"
    private const val MEDIA_DIR_NAME = "media"
    private const val DB_FILE_NAME = "penumbra.db"
    private const val STORAGE_MEDIA_PLACEHOLDER = "__APP_MEDIA_DIR__"
    private const val STORAGE_DB_PLACEHOLDER = "__APP_DB_PATH__"

    fun ensureCanonicalConfig(context: Context): String {
        val storageContext = context.createDeviceProtectedStorageContext()
        storageContext.filesDir.mkdirs()

        val configFile = File(storageContext.filesDir, CONFIG_FILE_NAME)
        if (configFile.exists()) {
            return configFile.absolutePath
        }

        val dataDir = File(storageContext.filesDir, DATA_DIR_NAME)
        val mediaDir = File(dataDir, MEDIA_DIR_NAME)
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }

        val dbFile = File(dataDir, DB_FILE_NAME)
        dbFile.parentFile?.mkdirs()

        val bootstrapToml = storageContext.assets.open(BOOTSTRAP_ASSET).bufferedReader().use { it.readText() }
        val renderedToml = bootstrapToml
            .replace(STORAGE_MEDIA_PLACEHOLDER, mediaDir.absolutePath)
            .replace(STORAGE_DB_PLACEHOLDER, dbFile.absolutePath)

        configFile.writeText(renderedToml)
        Log.i(TAG, "Wrote canonical config to ${configFile.absolutePath}")
        return configFile.absolutePath
    }
}
