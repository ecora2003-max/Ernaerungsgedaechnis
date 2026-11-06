package de.ernaehrung.demo;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/** A real ACTION_SEND recipient in the standalone TEST APK, not an LLM.
 * Only Android/Java classes: AGP omits the target APK's Kotlin dependencies
 * from instrumentation packaging, and this separate process cannot load them.
 */
public final class SyntheticRecipeAiReceiver extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk_gphone")) {
            throw new SecurityException("Synthetic exchange only on disposable emulators");
        }
        Intent incoming = getIntent();
        if (!Intent.ACTION_SEND.equals(incoming.getAction()) || !"text/plain".equals(incoming.getType())) {
            throw new IllegalArgumentException("Expected a real plain-text Android share");
        }
        String text = incoming.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || !text.contains("synthetic-device") || text.length() > 65_536) {
            throw new IllegalArgumentException("Only explicitly labelled synthetic requests");
        }
        SyntheticRecipeAiCapture.prompt = text;
        finish();
    }
}
