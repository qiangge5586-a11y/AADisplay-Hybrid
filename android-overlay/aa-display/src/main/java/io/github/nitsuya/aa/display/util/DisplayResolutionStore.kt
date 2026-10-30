package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Manual render-buffer override for the two AA virtual-display panes.
 *
 * 0x0 means automatic (use the settled HU/Coolwalk profile). The original
 * negotiated HU profile is never persisted or overwritten by this store.
 */
object DisplayResolutionStore {
    private const val TAG = "AADisplay_Resolution"
    private const val PATH = "/data/system/aadisplay_vd_resolution.properties"
    private const val SETTINGS_WIDTH = "aadisplay_vd_resolution_width"
    private const val SETTINGS_HEIGHT = "aadisplay_vd_resolution_height"
    private const val FILE_WIDTH = "VdResolutionWidth"
    private const val FILE_HEIGHT = "VdResolutionHeight"

    const val MIN_WIDTH = 320
    const val MAX_WIDTH = 7680
    const val MIN_HEIGHT = 240
    const val MAX_HEIGHT = 4320
    const val MAX_PIXELS = 20_000_000L

    data class Size(val width: Int, val height: Int) {
        val isAutomatic: Boolean get() = width == 0 && height == 0
    }

    private val lock = Any()
    @Volatile private var cached: Size? = null

    fun isValid(width: Int, height: Int): Boolean {
        if (width == 0 && height == 0) return true
        if (width !in MIN_WIDTH..MAX_WIDTH || height !in MIN_HEIGHT..MAX_HEIGHT) return false
        return width.toLong() * height.toLong() <= MAX_PIXELS
    }

    fun load(contentResolver: ContentResolver? = null): Size {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val loaded = loadSettings(contentResolver) ?: loadFile() ?: Size(0, 0)
            val safe = if (isValid(loaded.width, loaded.height)) loaded else Size(0, 0)
            cached = safe
            return safe
        }
    }

    /** Persist both dimensions atomically at the logical level; returns false if both stores fail. */
    fun save(width: Int, height: Int, contentResolver: ContentResolver? = null): Boolean {
        require(isValid(width, height)) { "Invalid virtual display resolution: ${width}x$height" }
        val size = Size(width, height)
        val settingsOk = writeSettings(size, contentResolver)
        val fileOk = writeFile(size)
        if (settingsOk || fileOk) {
            synchronized(lock) { cached = size }
            Log.i(TAG, "saved ${width}x$height settings=$settingsOk file=$fileOk")
            return true
        }
        Log.e(TAG, "save failed ${width}x$height")
        return false
    }

    fun clear(contentResolver: ContentResolver? = null): Boolean {
        val settingsOk = try {
            contentResolver != null &&
                Settings.Global.putString(contentResolver, SETTINGS_WIDTH, null) &&
                Settings.Global.putString(contentResolver, SETTINGS_HEIGHT, null)
        } catch (t: Throwable) {
            Log.w(TAG, "clear settings failed", t); false
        }
        val fileOk = try {
            val file = File(PATH)
            if (!file.exists()) true else file.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "clear file failed", t); false
        }
        if (settingsOk || fileOk) synchronized(lock) { cached = Size(0, 0) }
        return settingsOk || fileOk
    }

    fun invalidateCache() { synchronized(lock) { cached = null } }

    private fun loadSettings(cr: ContentResolver?): Size? {
        if (cr == null) return null
        return try {
            val w = Settings.Global.getString(cr, SETTINGS_WIDTH)?.toIntOrNull() ?: 0
            val h = Settings.Global.getString(cr, SETTINGS_HEIGHT)?.toIntOrNull() ?: 0
            Size(w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "load settings failed", t); null
        }
    }

    private fun loadFile(): Size? = try {
        val file = File(PATH)
        if (!file.isFile || file.length() <= 0L) return Size(0, 0)
        val props = Properties()
        FileInputStream(file).use { props.load(it) }
        Size(props.getProperty(FILE_WIDTH)?.toIntOrNull() ?: 0,
            props.getProperty(FILE_HEIGHT)?.toIntOrNull() ?: 0)
    } catch (t: Throwable) {
        Log.w(TAG, "load file failed", t); null
    }

    private fun writeSettings(size: Size, cr: ContentResolver?): Boolean = try {
        if (cr == null) return false
        val first = Settings.Global.putString(cr, SETTINGS_WIDTH, size.width.toString())
        val second = Settings.Global.putString(cr, SETTINGS_HEIGHT, size.height.toString())
        first && second
    } catch (t: Throwable) {
        Log.w(TAG, "save settings failed", t); false
    }

    private fun writeFile(size: Size): Boolean = try {
        val file = File(PATH)
        file.parentFile?.mkdirs()
        val props = Properties()
        props.setProperty(FILE_WIDTH, size.width.toString())
        props.setProperty(FILE_HEIGHT, size.height.toString())
        FileOutputStream(file).use { props.store(it, "AADisplay virtual display resolution") }
        file.setReadable(true, false)
        file.setWritable(true, true)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "save file failed", t); false
    }
}
