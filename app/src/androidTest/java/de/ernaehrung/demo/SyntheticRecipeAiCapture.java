package de.ernaehrung.demo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Signature-restricted cross-process capture in the TEST APK only.
 * Keep this standalone component free of target/Kotlin/support-library classes.
 */
public final class SyntheticRecipeAiCapture extends ContentProvider {
    static volatile String prompt;
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context own = getContext();
        if (own == null || own.getPackageManager().checkSignatures(Binder.getCallingUid(), Process.myUid())
                != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Synthetic capture requires the same test signing identity");
        }
        Bundle result = new Bundle();
        if ("peek".equals(method)) result.putString("prompt", prompt);
        else if ("clear".equals(method)) prompt = null;
        else throw new IllegalArgumentException("Unsupported synthetic test method");
        return result;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only synthetic capture"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only synthetic capture"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only synthetic capture"); }
}
