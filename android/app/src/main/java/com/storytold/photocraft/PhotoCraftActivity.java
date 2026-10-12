package com.storytold.photocraft;

import android.app.NativeActivity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The NativeActivity that hosts PhotoCraft (libphotocraft_android.so), plus the system pickers a
 * native activity can't reach on its own: the Photo Picker (gallery), the camera, the document
 * picker (any file, any app or cloud drive) and "Save as".
 *
 * The Rust side calls the public methods below through JNI and then polls {@link #takeResult()}
 * once per frame. Picked content is copied into the app cache so PhotoCraft can open it by path;
 * no storage permission is needed for any of this.
 */
public class PhotoCraftActivity extends NativeActivity {
    private static final String TAG = "PhotoCraft";
    private static final int REQ_IMAGES = 1;
    private static final int REQ_FILES = 2;
    private static final int REQ_CAMERA = 3;
    private static final int REQ_SAVE = 4;
    private static final int MAX_IMAGES = 20;

    /** The answer waiting for Rust: see {@link #takeResult()}. */
    private String[] result;
    private Uri cameraUri;
    private String cameraName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Copies from earlier sessions: nothing still has them open (there is no autosave).
        deleteTree(new File(getCacheDir(), "picked"));
    }

    // ----- Called from Rust ------------------------------------------------------------------

    /** The gallery: the system Photo Picker on Android 13+, the image chooser before that. */
    public void pickImages(final boolean multiple) {
        runOnUiThread(() -> {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 33) {
                intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
                if (multiple) {
                    intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MAX_IMAGES);
                }
            } else {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            }
            launch(intent, REQ_IMAGES);
        });
    }

    /** Any file from any storage provider (Downloads, Drive, a USB stick…). */
    public void pickFiles(final boolean multiple) {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            launch(intent, REQ_FILES);
        });
    }

    /** Take a photo with the camera app; the full-size picture is also kept in the gallery. */
    public void takePhoto() {
        runOnUiThread(() -> {
            try {
                cameraName = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, cameraName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (cameraUri == null) {
                    post(new String[] {"error", "The gallery refused a new photo."});
                    return;
                }
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                launch(intent, REQ_CAMERA);
            } catch (RuntimeException e) {
                post(new String[] {"error", "Couldn't start the camera: " + e.getMessage()});
            }
        });
    }

    /** The system "Save as" dialog, suggesting {@code name}. */
    public void createDocument(final String name, final String mime) {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_TITLE, name);
            launch(intent, REQ_SAVE);
        });
    }

    /** Copy a saved file to the document chosen in "Save as". Returns null, or the error. */
    public String copyToUri(String path, String uri) {
        try (InputStream in = new FileInputStream(path);
             OutputStream out = getContentResolver().openOutputStream(Uri.parse(uri), "wt")) {
            if (out == null) {
                return "The destination can't be written.";
            }
            copy(in, out);
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    /**
     * The finished request, once, or null while there is none:
     * {"files", name1, path1, name2, path2, …}, {"save", uri, name}, {"cancel"} or {"error", message}.
     */
    public synchronized String[] takeResult() {
        String[] r = result;
        result = null;
        return r;
    }

    // ----- Results ---------------------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            final Uri uri = cameraUri;
            final String name = cameraName;
            cameraUri = null;
            if (resultCode != RESULT_OK || uri == null) {
                if (uri != null) {
                    try {
                        getContentResolver().delete(uri, null, null);
                    } catch (RuntimeException ignored) {
                        // Nothing was taken; an empty entry left behind is harmless.
                    }
                }
                post(new String[] {"cancel"});
                return;
            }
            final List<Uri> one = new ArrayList<>();
            one.add(uri);
            copyInBackground(one, name);
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            post(new String[] {"cancel"});
            return;
        }
        if (requestCode == REQ_SAVE) {
            Uri uri = data.getData();
            if (uri == null) {
                post(new String[] {"cancel"});
                return;
            }
            post(new String[] {"save", uri.toString(), displayName(uri, "image")});
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) {
                    uris.add(u);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            post(new String[] {"cancel"});
            return;
        }
        copyInBackground(uris, null);
    }

    /** Copy picked content into the cache off the UI thread (photos and PSDs can be large). */
    private void copyInBackground(final List<Uri> uris, final String forcedName) {
        new Thread(() -> {
            File dir = new File(new File(getCacheDir(), "picked"), Long.toString(System.nanoTime()));
            if (!dir.mkdirs() && !dir.isDirectory()) {
                post(new String[] {"error", "Couldn't create " + dir});
                return;
            }
            ArrayList<String> out = new ArrayList<>();
            out.add("files");
            ContentResolver resolver = getContentResolver();
            int index = 0;
            for (Uri uri : uris) {
                String name = forcedName != null ? forcedName : displayName(uri, "image-" + index);
                // One folder per file, so two pictures with the same name don't collide and the
                // document keeps its real name.
                File sub = new File(dir, Integer.toString(index));
                index++;
                if (!sub.mkdirs() && !sub.isDirectory()) {
                    post(new String[] {"error", "Couldn't create " + sub});
                    return;
                }
                File target = new File(sub, safeName(name));
                try (InputStream in = resolver.openInputStream(uri);
                     OutputStream os = new FileOutputStream(target)) {
                    if (in == null) {
                        throw new IOException("no data");
                    }
                    copy(in, os);
                    out.add(name);
                    out.add(target.getAbsolutePath());
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "couldn't read " + uri, e);
                    post(new String[] {"error", "Couldn't read " + name + ": " + e.getMessage()});
                    return;
                }
            }
            post(out.toArray(new String[0]));
        }, "photocraft-pick").start();
    }

    // ----- Helpers ---------------------------------------------------------------------------

    private void launch(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            post(new String[] {"error", "No app on this device can do that."});
        } catch (RuntimeException e) {
            post(new String[] {"error", e.toString()});
        }
    }

    private synchronized void post(String[] r) {
        result = r;
    }

    /** The name the provider shows for {@code uri}, or {@code fallback}. */
    private String displayName(Uri uri, String fallback) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (RuntimeException ignored) {
            // Some providers don't answer queries; the fallback name will do.
        }
        String last = uri.getLastPathSegment();
        return last != null && !last.isEmpty() ? last : fallback;
    }

    /** A file name without separators or control characters. */
    private static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return s.isEmpty() ? "image" : s;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteTree(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
