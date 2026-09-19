package com.yeragorla.whatsappcontentviewer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private TextView status;
    private TextView details;
    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        details = findViewById(R.id.details);
        imageView = findViewById(R.id.imageView);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }

        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            status.setText("No shared content URI received.");
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (Exception ignored) {
            // WhatsApp/provider URIs commonly do not support persistable permissions.
        }

        status.setText("Reading shared content...");
        new Thread(() -> processUri(uri)).start();
    }

    private void processUri(Uri uri) {
        try {
            String mimeType = getContentResolver().getType(uri);
            long size = querySize(uri);

            // Read the provider stream immediately. Do not convert the URI to a file path.
            try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    throw new IOException("ContentResolver returned a null stream");
                }

                File savedFile = copyToInternalStorage(inputStream, mimeType);
                Bitmap bitmap = null;

                if (mimeType != null && mimeType.startsWith("image/")) {
                    try (FileInputStream savedInput = new FileInputStream(savedFile)) {
                        bitmap = BitmapFactory.decodeStream(savedInput);
                    }
                }

                Bitmap finalBitmap = bitmap;
                runOnUiThread(() -> {
                    if (finalBitmap != null) {
                        imageView.setImageBitmap(finalBitmap);
                        imageView.setVisibility(View.VISIBLE);
                    }
                    status.setText("Content received successfully.");
                    details.setText(
                            "URI: " + uri + "\n" +
                            "Type: " + (mimeType == null ? "unknown" : mimeType) + "\n" +
                            "Size: " + (size >= 0 ? size + " bytes" : "unknown") + "\n" +
                            "Saved: " + savedFile.getName()
                    );
                });
            }
        } catch (SecurityException e) {
            showError("Permission denied for the shared content.");
        } catch (IOException e) {
            showError("Unable to read shared content: " + e.getMessage());
        } catch (RuntimeException e) {
            showError("Unable to process shared content.");
        }
    }

    private File copyToInternalStorage(InputStream input, String mimeType) throws IOException {
        String extension = extensionForMimeType(mimeType);
        File output = new File(getFilesDir(), "shared_" + System.currentTimeMillis() + extension);

        try (FileOutputStream outputStream = new FileOutputStream(output)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
        }
        return output;
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private String extensionForMimeType(String mimeType) {
        if (mimeType == null) return ".bin";
        if (mimeType.equals("image/jpeg")) return ".jpg";
        if (mimeType.equals("image/png")) return ".png";
        if (mimeType.equals("image/webp")) return ".webp";
        if (mimeType.equals("video/mp4")) return ".mp4";
        if (mimeType.equals("application/pdf")) return ".pdf";
        return ".bin";
    }

    private void showError(String message) {
        runOnUiThread(() -> status.setText(message));
    }
}
