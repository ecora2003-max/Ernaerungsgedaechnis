package de.ernaehrung.demo

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process

/** A real ACTION_SEND recipient, never an LLM or live-generation proof. */
class SyntheticRecipeAiReceiver : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        check(intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
        val text = requireNotNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        check(text.contains("synthetic-device") && text.length <= 65_536)
        SyntheticRecipeAiCapture.prompt = text
        finish()
    }
}

/** Cross-process capture in the TEST APK, restricted to the same test signing
 * identity. Only an explicitly selected, labelled synthetic request is accepted.
 */
class SyntheticRecipeAiCapture : ContentProvider() {
    companion object { @Volatile var prompt: String? = null }
    override fun onCreate(): Boolean = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val manager = requireNotNull(context).packageManager
        check(manager.checkSignatures(Binder.getCallingUid(), Process.myUid()) == PackageManager.SIGNATURE_MATCH)
        return Bundle().apply {
            when (method) {
                "peek" -> putString("prompt", prompt)
                "clear" -> prompt = null
                else -> error("Unsupported synthetic test method")
            }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only synthetic capture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only synthetic capture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only synthetic capture")
}
