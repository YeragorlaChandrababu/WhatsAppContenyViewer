# WhatsAppContenyViewer

Android reference implementation for receiving WhatsApp-shared media through Android `content://` URIs.

## Important

WhatsApp media such as:

```
content://com.whatsapp.provider.media/item/...
```

must be consumed as a content URI. Do not attempt to convert it into a filesystem path.

Use the `ContentResolver` supplied by the receiving Activity and open the stream while the share-granted access is available:

```java
Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

if (uri != null) {
    try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
        if (inputStream == null) {
            throw new IOException("Unable to open shared content");
        }

        // Decode/process the media here.
        Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
    }
}
```

Android documents `ContentResolver.openInputStream(Uri)` as the API for opening data associated with a content URI.

If the media needs to remain available after the incoming share operation, copy the stream into app-private storage immediately rather than persisting the WhatsApp URI as if it were a normal filesystem path.

## Supported share flow

1. Receive `Intent.ACTION_SEND`.
2. Read `Intent.EXTRA_STREAM`.
3. Validate the URI.
4. Open it with `ContentResolver.openInputStream()`.
5. Decode/process the stream.
6. Copy to app-private storage when persistence is required.
7. Handle `SecurityException`, `FileNotFoundException`, and I/O failures gracefully.
