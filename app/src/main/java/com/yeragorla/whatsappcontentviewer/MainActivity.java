package com.yeragorla.whatsappcontentviewer;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.graphics.Typeface;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_TREE = 9001;
    private LinearLayout breadcrumbs;
    private TextView pathText;
    private ListView fileList;
    private final ArrayList<Item> items = new ArrayList<>();
    private File currentFileDir;
    private DocumentFile currentDocDir;
    private boolean documentMode = false;
    private Uri documentRootUri;
    private File clipboardFile;
    private DocumentFile clipboardDoc;
    private boolean clipboardCut = false;
    private boolean pickingDualStorage = false;
    private String storageLabel = "Internal Storage";
    private boolean dualProfileMode = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String savedTheme = getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "system");
        applyTheme(savedTheme);
        setContentView(R.layout.activity_main);
        breadcrumbs = findViewById(R.id.breadcrumbs);
        pathText = findViewById(R.id.pathText);
        fileList = findViewById(R.id.fileList);

        findViewById(R.id.internalStorage).setOnClickListener(v -> openInternal());
        findViewById(R.id.dualStorage).setOnClickListener(v -> openDualApps());
        findViewById(R.id.addStorage).setOnClickListener(v -> pickStorage("Select a storage location"));
        findViewById(R.id.themeButton).setOnClickListener(v -> showThemeChooser());
        findViewById(R.id.newFolder).setOnClickListener(v -> createFolder());
        findViewById(R.id.newFile).setOnClickListener(v -> createFile());
        findViewById(R.id.pasteButton).setOnClickListener(v -> pasteClipboard());
        fileList.setOnItemClickListener((p,v,pos,id) -> openItem(items.get(pos)));
        fileList.setOnItemLongClickListener((p,v,pos,id) -> { showActions(items.get(pos)); return true; });

        openInternal();
        handleShareIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); handleShareIntent(intent);
    }

    private void showThemeChooser() {
        String saved = getSharedPreferences("settings", MODE_PRIVATE).getString("theme", "system");
        String[] labels = {"System default", "Light", "Dark"};
        String[] values = {"system", "light", "dark"};
        int checked = 0;
        for (int i=0;i<values.length;i++) if(values[i].equals(saved)) checked=i;
        new AlertDialog.Builder(this).setTitle("Theme").setSingleChoiceItems(labels, checked, (d, which) -> {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("theme", values[which]).apply();
            applyTheme(values[which]); d.dismiss();
        }).show();
    }

    private void applyTheme(String theme) {
        if ("dark".equals(theme)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        else if ("light".equals(theme)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    private void handleShareIntent(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("No input stream");
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "StorageExplorer");
                if (!dir.exists()) dir.mkdirs();
                String name = "shared_" + System.currentTimeMillis() + extensionFor(getContentResolver().getType(uri));
                copy(in, new File(dir,name));
                runOnUiThread(this::refresh);
            } catch (Exception e) {
                runOnUiThread(() -> toast("Could not import shared content"));
            }
        }).start();
    }

    private void openInternal() {
        documentMode = false; documentRootUri = null;
        dualProfileMode = false;
        storageLabel = "Internal Storage";
        currentFileDir = Environment.getExternalStorageDirectory();
        currentDocDir = null;
        refresh();
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this).setTitle("Storage access")
                .setMessage("To browse and manage shared internal storage, allow Storage Explorer access to all files. Android still protects private /data and other app-private areas.")
                .setPositiveButton("Allow", (d,w) -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()))); } catch(Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }).setNegativeButton("Later", null).show();
        }
    }

    private void openDualApps() {
        // Dual Apps is commonly exposed by Android as user/profile 999 on Xiaomi/Redmi/POCO.
        // Never create or assume the path: verify that the profile is actually mounted and readable.
        File dualRoot = new File(Environment.getExternalStorageDirectory().getParentFile(), "999");
        if (dualRoot.isDirectory() && dualRoot.canRead()) {
            File[] probe = dualRoot.listFiles();
            if (probe != null) {
                documentMode = false;
                documentRootUri = null;
                dualProfileMode = true;
                currentFileDir = dualRoot;
                currentDocDir = null;
                storageLabel = "Dual Apps (user 999)";
                refresh();
                return;
            }
        }

        // Some Android/OEM builds isolate user 999 from normal filesystem APIs.
        // In that case let Android grant access through the Storage Access Framework.
        String saved = getSharedPreferences("storage", MODE_PRIVATE).getString("dual_tree_uri", null);
        if (saved != null) {
            try {
                Uri u = Uri.parse(saved);
                int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(u, takeFlags);
                DocumentFile root = DocumentFile.fromTreeUri(this, u);
                if (root != null && root.canRead()) {
                    documentMode = true; documentRootUri = u; currentDocDir = root; currentFileDir = null;
                    dualProfileMode = false;
                    storageLabel = "Dual Apps"; refresh(); return;
                }
            } catch (Exception ignored) {}
        }
        pickingDualStorage = true;
        pickStorage("Select \"Storage for dual apps\"");
    }

    private void pickStorage(String title) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                   Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_TREE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == PICK_TREE && resultCode == RESULT_OK && data != null && data.getData()!=null) {
            Uri u=data.getData();
            try { getContentResolver().takePersistableUriPermission(u, data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); } catch(Exception ignored){}
            documentMode=true; documentRootUri=u; currentDocDir=DocumentFile.fromTreeUri(this,u); currentFileDir=null;
            dualProfileMode = false;
            if (pickingDualStorage) {
                getSharedPreferences("storage", MODE_PRIVATE).edit().putString("dual_tree_uri", u.toString()).apply();
                storageLabel = "Dual Apps";
            } else {
                storageLabel = "Selected Storage";
            }
            pickingDualStorage = false;
            refresh();
        }
    }

    private void refresh() {
        items.clear();
        if (documentMode) {
            if (currentDocDir != null) {
                DocumentFile[] fs=currentDocDir.listFiles();
                Arrays.sort(fs,(a,b) -> Boolean.compare(!a.isDirectory(),!b.isDirectory()) != 0
                        ? Boolean.compare(!a.isDirectory(),!b.isDirectory()) : a.getName().compareToIgnoreCase(b.getName()));
                for(DocumentFile f:fs) items.add(Item.doc(f));
                pathText.setText(storageLabel + "  •  " + currentDocDir.getUri().toString());
            }
        } else if(currentFileDir != null) {
            File[] fs=currentFileDir.listFiles();
            if(fs!=null) {
                Arrays.sort(fs,(a,b) -> a.isDirectory()!=b.isDirectory() ? (a.isDirectory()?-1:1) :
                        a.getName().compareToIgnoreCase(b.getName()));
                for(File f:fs) items.add(Item.file(f));
            }
            pathText.setText(storageLabel + "  •  " + currentFileDir.getAbsolutePath());
        }
        renderBreadcrumbs();
        ArrayAdapter<Item> adapter=new ArrayAdapter<Item>(this,0,items) {
            @Override public View getView(int position,View convert,android.view.ViewGroup parent) {
                View row=convert;
                if(row==null) row=getLayoutInflater().inflate(R.layout.item_file,parent,false);
                Item it=getItem(position);
                ImageView itemIcon=row.findViewById(R.id.itemIcon);
                TextView itemName=row.findViewById(R.id.itemName);
                TextView itemMeta=row.findViewById(R.id.itemMeta);
                itemIcon.setImageResource(it.isDir() ? R.drawable.ic_folder : iconRes(it.name()));
                itemName.setText(it.name());
                itemMeta.setText(it.isDir() ? "Folder" : size(it.length()));
                return row;
            }
        };
        fileList.setAdapter(adapter);
    }

    private void renderBreadcrumbs() {
        breadcrumbs.removeAllViews();
        TextView home=crumb("⌂");
        home.setOnClickListener(v -> openInternal());
        breadcrumbs.addView(home);
        if(documentMode) {
            TextView d=crumb("  /  " + storageLabel);
            d.setOnClickListener(v -> { currentDocDir=DocumentFile.fromTreeUri(this,documentRootUri); refresh(); });
            breadcrumbs.addView(d);
        } else if(currentFileDir!=null) {
            File root=Environment.getExternalStorageDirectory(), f=currentFileDir;
            ArrayList<File> chain=new ArrayList<>();
            while(f!=null && !f.equals(root.getParentFile())) { chain.add(f); if(f.equals(root)) break; f=f.getParentFile(); }
            Collections.reverse(chain);
            for(File x:chain) {
                TextView c=crumb("  /  "+x.getName());
                File target=x; c.setOnClickListener(v -> { currentFileDir=target; refresh(); });
                breadcrumbs.addView(c);
            }
        }
    }

    private TextView crumb(String s) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(15); t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(8,0,8,0); t.setTextColor(Color.rgb(70,100,190)); return t;
    }

    private void openItem(Item it) {
        if(it.isDir()) { if(documentMode) currentDocDir=it.doc; else currentFileDir=it.file; refresh(); return; }
        if (dualProfileMode && !documentMode) { openDualFileWithProvider(it.file, false); return; }
        try {
            Uri u = documentMode ? it.doc.getUri() : FileProvider.getUriForFile(this,getPackageName()+".fileprovider",it.file);
            String mime = documentMode ? getContentResolver().getType(u) : guessMime(it.name());
            if (mime == null || mime.equals("*/*")) {
                String detected = getContentResolver().getType(u);
                if (detected != null) mime = detected;
            }
            if (mime == null) mime = "*/*";
            if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
                Intent viewer = new Intent(this, MediaViewerActivity.class);
                viewer.putExtra("media_uri", u);
                viewer.putExtra("media_mime", mime);
                viewer.putExtra("media_name", it.name());
                viewer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                viewer.setClipData(ClipData.newRawUri("media", u));
                startActivity(viewer);
            } else {
                Intent i=new Intent(Intent.ACTION_VIEW,u);
                i.setDataAndType(u,mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.setClipData(ClipData.newRawUri("file",u));
                startActivity(Intent.createChooser(i,"Open with another app"));
            }
        } catch(Exception e) { toast("No compatible app can open this file"); }
    }

    private void openDualFileWithProvider(File source, boolean share) {
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "dual-share");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create cache");
                String safe = source.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                File cached = new File(dir, System.currentTimeMillis() + "_" + safe);
                copyFile(source, cached);
                Uri u = Uri.fromFile(cached);
                runOnUiThread(() -> {
                    Intent i;
                    if (share) {
                        i = new Intent(Intent.ACTION_SEND);
                        i.setType(guessMime(source.getName()));
                        i.putExtra(Intent.EXTRA_STREAM, u);
                        i.setClipData(ClipData.newRawUri("file", u));
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Share file"));
                    } else {
                        String mime = guessMime(source.getName());
                        Intent viewer = new Intent(this, MediaViewerActivity.class);
                        viewer.putExtra("media_uri", u); viewer.putExtra("media_mime", mime); viewer.putExtra("media_name", source.getName());
                        viewer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); viewer.setClipData(ClipData.newRawUri("media", u));
                        try { startActivity(viewer); } catch(Exception e) { toast("Could not open media"); }
                    }
                });
            } catch(Exception e) { runOnUiThread(() -> toast("Could not prepare file: " + e.getMessage())); }
        }).start();
    }

    private void showActions(Item it) {
        String[] actions=it.isDir() ? new String[]{"Open","Copy","Cut","Rename","Delete"} :
                new String[]{"Open","Copy","Cut","Rename","Delete","Share"};
        new AlertDialog.Builder(this).setTitle(it.name()).setItems(actions,(d,w) -> {
            String a=actions[w];
            if(a.equals("Open")) openItem(it);
            else if(a.equals("Copy")||a.equals("Cut")) { clipboardFile=it.file; clipboardDoc=it.doc; clipboardCut=a.equals("Cut"); toast(clipboardCut?"Cut":"Copied"); }
            else if(a.equals("Rename")) rename(it);
            else if(a.equals("Delete")) delete(it);
            else share(it);
        }).show();
    }

    private void rename(Item it) {
        final EditText e=new EditText(this); e.setText(it.name()); e.selectAll(); e.setSingleLine();
        new AlertDialog.Builder(this).setTitle("Rename").setView(e).setPositiveButton("Save",(d,w)->{
            String n=e.getText().toString().trim(); if(n.isEmpty()) return;
            boolean ok=documentMode ? it.doc.renameTo(n) : it.file.renameTo(new File(it.file.getParentFile(),n));
            toast(ok?"Renamed":"Rename failed"); refresh();
        }).setNegativeButton("Cancel",null).show();
    }

    private void delete(Item it) {
        new AlertDialog.Builder(this).setTitle("Delete").setMessage("Delete \"" + it.name() + "\"? This cannot be undone.")
            .setPositiveButton("Delete",(d,w)->{ boolean ok=deleteRecursive(it); toast(ok?"Deleted":"Delete failed"); refresh(); })
            .setNegativeButton("Cancel",null).show();
    }

    private boolean deleteRecursive(Item it) {
        if(documentMode) return it.doc.delete();
        if(it.file.isDirectory()) { File[] fs=it.file.listFiles(); if(fs!=null) for(File f:fs) deleteFile(f); }
        return it.file.delete();
    }
    private void deleteFile(File f) { if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteFile(x);} f.delete(); }

    private void share(Item it) {
        if (dualProfileMode && !documentMode) {
            openDualFileWithProvider(it.file, true);
            return;
        }
        try {
            Uri u=documentMode?it.doc.getUri():FileProvider.getUriForFile(this,getPackageName()+".fileprovider",it.file);
            Intent i=new Intent(Intent.ACTION_SEND); i.setType(documentMode?(getContentResolver().getType(u)!=null?getContentResolver().getType(u):"*/*"):guessMime(it.name()));
            i.putExtra(Intent.EXTRA_STREAM,u); i.setClipData(ClipData.newRawUri("file", u)); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(i,"Share file"));
        } catch(Exception e){toast("Share failed");}
    }

    private void createFolder() {
        final EditText e=new EditText(this); e.setHint("Folder name"); e.setSingleLine();
        new AlertDialog.Builder(this).setTitle("New folder").setView(e).setPositiveButton("Create",(d,w)->{
            String n=e.getText().toString().trim(); if(n.isEmpty()) return;
            boolean ok=documentMode ? currentDocDir.createDirectory(n)!=null : new File(currentFileDir,n).mkdir();
            toast(ok?"Folder created":"Could not create folder"); refresh();
        }).setNegativeButton("Cancel",null).show();
    }

    private void createFile() {
        final EditText e=new EditText(this); e.setHint("File name, e.g. note.txt"); e.setSingleLine();
        new AlertDialog.Builder(this).setTitle("New file").setView(e).setPositiveButton("Create",(d,w)->{
            String n=e.getText().toString().trim(); if(n.isEmpty()) return;
            try {
                boolean ok;
                if(documentMode) ok=currentDocDir.createFile(guessMime(n),n)!=null;
                else ok=new File(currentFileDir,n).createNewFile();
                toast(ok?"File created":"Could not create file"); refresh();
            } catch(IOException ex){toast("Could not create file");}
        }).setNegativeButton("Cancel",null).show();
    }

    private void pasteClipboard() {
        if((clipboardFile==null && clipboardDoc==null)) { toast("Nothing to paste"); return; }
        try {
            if(documentMode) {
                if(clipboardDoc==null) { toast("Clipboard is from another storage type"); return; }
                copyDocument(clipboardDoc,currentDocDir);
                if(clipboardCut) clipboardDoc.delete();
            } else {
                if(clipboardFile==null) { toast("Clipboard is from another storage type"); return; }
                copyFile(clipboardFile,new File(currentFileDir,clipboardFile.getName()));
                if(clipboardCut) deleteFile(clipboardFile);
            }
            clipboardFile=null; clipboardDoc=null; clipboardCut=false; refresh(); toast("Pasted");
        } catch(Exception e){toast("Paste failed: "+e.getMessage());}
    }

    private void copyFile(File src,File dst) throws IOException {
        if(src.isDirectory()) {
            if(dst.exists() && dst.isDirectory()) dst=new File(dst,src.getName());
            if(!dst.exists() && !dst.mkdirs()) throw new IOException("Cannot create folder");
            File[] fs=src.listFiles(); if(fs!=null) for(File f:fs) copyFile(f,new File(dst,f.getName()));
        } else {
            if(dst.exists() && dst.isDirectory()) dst=new File(dst,src.getName());
            try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(dst)){
                byte[] b=new byte[32768]; int n; while((n=in.read(b))!=-1) out.write(b,0,n);
            }
        }
    }

    private void copyDocument(DocumentFile src,DocumentFile dest) throws IOException {
        DocumentFile target=src.isDirectory()?dest.createDirectory(src.getName()):dest.createFile(guessMime(src.getName()),src.getName());
        if(target==null) throw new IOException("Cannot create destination");
        if(src.isDirectory()){DocumentFile[] fs=src.listFiles();for(DocumentFile f:fs)copyDocument(f,target);}
        else try(InputStream in=getContentResolver().openInputStream(src.getUri());OutputStream out=getContentResolver().openOutputStream(target.getUri())){
            if(in==null||out==null)throw new IOException("Cannot open stream"); byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }
    }

    private int iconRes(String n) {
        String m=guessMime(n);
        if(m.startsWith("image/")) return R.drawable.ic_image;
        if(m.startsWith("video/")) return R.drawable.ic_video;
        if(m.startsWith("audio/")) return R.drawable.ic_audio;
        if(m.equals("application/pdf")) return R.drawable.ic_pdf;
        if(m.startsWith("text/")) return R.drawable.ic_file;
        return R.drawable.ic_archive;
    }

    private String guessMime(String n) {
        String x=n.toLowerCase(Locale.US);
        if(x.endsWith(".jpg")||x.endsWith(".jpeg"))return "image/jpeg"; if(x.endsWith(".png"))return "image/png"; if(x.endsWith(".webp"))return "image/webp";
        if(x.endsWith(".gif"))return "image/gif"; if(x.endsWith(".mp4")||x.endsWith(".mkv")||x.endsWith(".3gp"))return "video/*";
        if(x.endsWith(".mp3")||x.endsWith(".wav")||x.endsWith(".m4a"))return "audio/*"; if(x.endsWith(".pdf"))return "application/pdf";
        if(x.endsWith(".txt")||x.endsWith(".log")||x.endsWith(".json")||x.endsWith(".xml"))return "text/plain"; return "*/*";
    }
    private String extensionFor(String mime){if(mime==null)return ".bin"; if(mime.equals("image/jpeg"))return ".jpg";if(mime.equals("image/png"))return ".png";if(mime.equals("video/mp4"))return ".mp4";return ".bin";}
    private String size(long n){if(n<1024)return n+" B"; if(n<1024*1024)return (n/1024)+" KB"; if(n<1024*1024*1024)return (n/(1024*1024))+" MB"; return (n/(1024*1024*1024))+" GB";}
    private void copy(InputStream in,File f)throws IOException{try(OutputStream out=new FileOutputStream(f)){byte[]b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    private static class Item {
        File file; DocumentFile doc;
        static Item file(File f){Item i=new Item();i.file=f;return i;}
        static Item doc(DocumentFile d){Item i=new Item();i.doc=d;return i;}
        boolean isDir(){return file!=null?file.isDirectory():doc.isDirectory();}
        String name(){return file!=null?file.getName():(doc.getName()!=null?doc.getName():"Unnamed");}
        long length(){return file!=null?file.length():(doc.length());}
        @Override public String toString(){return name();}
    }
}
