package de.ernaehrung.demo;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** TEST APK only. The real DocumentsUI grants a selected read-only document to
 * the target, including API26 before MediaStore.Downloads exists. No URI result
 * is injected and no storage permission or personal file is exposed. */
public final class SyntheticRecipeDocuments extends DocumentsProvider {
    static final String TITLE = "EG · Synthetische Dateien";
    private static final String ROOT = "synthetic-recipe-files";
    private static final String[] ROOT_COLUMNS = {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES};
    private static final String[] DOCUMENT_COLUMNS = {Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS};

    static void requireSyntheticDevice(Context context) {
        if (context == null || (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0 ||
                !(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))) {
            throw new SecurityException("Synthetic documents require a debuggable disposable emulator");
        }
    }
    private static File directory(Context context) {
        requireSyntheticDevice(context);
        return new File(context.getCacheDir(), "synthetic-recipe-documents");
    }
    private static boolean validName(String value) {
        return value != null && value.matches("synthetic-recipe-ai-[0-9a-f-]{36}\\.json");
    }
    static void stage(Context context, String name, String raw) throws IOException {
        requireSyntheticDevice(context);
        if (!validName(name) || raw == null || raw.length() > 262144) throw new IllegalArgumentException("Invalid synthetic fixture");
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 262144) throw new IllegalArgumentException("Synthetic fixture too large");
        File folder = directory(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create synthetic fixture directory");
        AtomicFile file = new AtomicFile(new File(folder, name));
        FileOutputStream out = null;
        try {
            out = file.startWrite(); out.write(bytes); file.finishWrite(out);
        } catch (IOException failure) { if (out != null) file.failWrite(out); throw failure; }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    static void clear(Context context, String name) {
        if (!validName(name)) throw new IllegalArgumentException("Unknown synthetic document");
        new AtomicFile(new File(directory(context), name)).delete();
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        requireSyntheticDevice(getContext());
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Root.COLUMN_ROOT_ID: case Root.COLUMN_DOCUMENT_ID: row.add(column, ROOT); break;
                case Root.COLUMN_TITLE: row.add(column, TITLE); break;
                case Root.COLUMN_FLAGS: row.add(column, Root.FLAG_LOCAL_ONLY); break;
                case Root.COLUMN_MIME_TYPES: row.add(column, "application/json"); break;
                default: row.add(column, null);
            }
        }
        return cursor;
    }
    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean root = ROOT.equals(id);
        File file = new File(directory(getContext()), id);
        if (!root && (!validName(id) || !file.isFile())) throw new FileNotFoundException("Unknown synthetic document");
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(column, id); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(column, root ? TITLE : id); break;
                case Document.COLUMN_MIME_TYPE: row.add(column, root ? Document.MIME_TYPE_DIR : "application/json"); break;
                case Document.COLUMN_SIZE: row.add(column, root ? null : file.length()); break;
                case Document.COLUMN_FLAGS: row.add(column, 0); break;
                default: row.add(column, null);
            }
        }
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        addDocument(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) throws FileNotFoundException {
        requireSyntheticDevice(getContext());
        if (!ROOT.equals(parent)) throw new FileNotFoundException("Unknown synthetic root");
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        File[] files = directory(getContext()).listFiles();
        if (files != null) for (File file : files) if (validName(file.getName()) && file.isFile()) addDocument(cursor, file.getName());
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        requireSyntheticDevice(getContext());
        if (!"r".equals(mode) || !validName(id)) throw new FileNotFoundException("Read-only synthetic document");
        return ParcelFileDescriptor.open(new File(directory(getContext()), id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
