package com.yeragorla.whatsappcontentviewer;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.snackbar.Snackbar;
import java.io.*;
import java.util.*;

public class MediaViewerActivity extends AppCompatActivity {
    private ArrayList<Uri> uris = new ArrayList<>();
    private ArrayList<String> names = new ArrayList<>();
    private ArrayList<String> mimes = new ArrayList<>();
    private int index;
    private Uri uri;
    private String mime;
    private String name;
    private MediaPlayer player;
    private VideoView video;
    private ImageView image;
    private LinearLayout audioPanel, pdfPanel;
    private ImageView pdfPage;
    private TextView pageText, titleText, countText;
    private float mediaRotation;
    private float downX, downY;
    private long downTime;
    private PdfRenderer pdfRenderer;
    private PdfRenderer.Page pdfPageRenderer;
    private ParcelFileDescriptor pdfFd;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_media_viewer);
        image=findViewById(R.id.imageView);
        video=findViewById(R.id.videoView);
        audioPanel=findViewById(R.id.audioPanel);
        pdfPanel=findViewById(R.id.pdfPanel);
        pdfPage=findViewById(R.id.pdfPage);
        pageText=findViewById(R.id.pageText);
        titleText=findViewById(R.id.titleText);
        countText=findViewById(R.id.countText);

        ArrayList<Uri> incoming=getIntent().getParcelableArrayListExtra("media_uris");
        if(incoming!=null) uris=incoming;
        ArrayList<String> incomingNames=getIntent().getStringArrayListExtra("media_names");
        if(incomingNames!=null) names=incomingNames;
        ArrayList<String> incomingMimes=getIntent().getStringArrayListExtra("media_mimes");
        if(incomingMimes!=null) mimes=incomingMimes;
        index=Math.max(0,Math.min(getIntent().getIntExtra("media_index",0),Math.max(0,uris.size()-1)));

        findViewById(R.id.closeButton).setOnClickListener(v->finish());
        findViewById(R.id.shareButton).setOnClickListener(v->shareCurrent());
        findViewById(R.id.detailsButton).setOnClickListener(v->showDetails());
        findViewById(R.id.rotateButton).setOnClickListener(v->rotateCurrent());
        titleText.setOnClickListener(v->showDetails());
        View swipeSurface=findViewById(R.id.swipeSurface);
        swipeSurface.setOnTouchListener((v,event)->handleSwipe(event));

        if(uris.isEmpty()){ notifyUser("Media could not be opened"); finish(); return; }
        showItem(index);
    }

    private void showItem(int newIndex) {
        if(newIndex<0 || newIndex>=uris.size()) return;
        releasePlayback();
        closePdf();
        index=newIndex;
        uri=uris.get(index);
        mime=index<mimes.size()?mimes.get(index):null;
        name=index<names.size()?names.get(index):"Media";
        if(mime==null || mime.equals("*/*")) {
            String detected=getContentResolver().getType(uri);
            if(detected!=null) mime=detected;
        }
        if(mime==null) mime="*/*";
        titleText.setText(name);
        countText.setText((index + 1) + " / " + uris.size());
        mediaRotation=0f;
        image.setVisibility(View.GONE); video.setVisibility(View.GONE); audioPanel.setVisibility(View.GONE); pdfPanel.setVisibility(View.GONE);

        try {
            if(mime.startsWith("image/")) {
                image.setVisibility(View.VISIBLE);
                try { image.setImageURI(uri); } catch (RuntimeException e) { notifyUser("Could not load image"); }
            } else if(mime.startsWith("video/")) {
                video.setVisibility(View.VISIBLE);
                video.setMediaController(new MediaController(this));
                video.setOnPreparedListener(mp->{
                    centerVideo(mp.getVideoWidth(), mp.getVideoHeight());
                    video.start();
                });
                video.setOnErrorListener((mp, what, extra)->{ notifyUser("Could not play this video"); return true; });
                video.setVideoURI(uri);
                video.requestFocus();
            } else if(mime.startsWith("audio/")) {
                audioPanel.setVisibility(View.VISIBLE);
                ((TextView)findViewById(R.id.mediaName)).setText(name);
                findViewById(R.id.audioButton).setOnClickListener(v->toggleAudio());
            } else if(mime.equals("application/pdf") || name.toLowerCase(Locale.US).endsWith(".pdf")) {
                pdfPanel.setVisibility(View.VISIBLE);
                openPdf();
            } else {
                notifyUser("This format is not supported in the viewer");
            }
        } catch (Exception e) {
            image.setVisibility(View.GONE);
            video.setVisibility(View.GONE);
            audioPanel.setVisibility(View.GONE);
            pdfPanel.setVisibility(View.GONE);
            notifyUser("Could not load this media");
        }
    }

    private boolean handleSwipe(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=event.getX(); downY=event.getY(); downTime=System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_UP:
                float dx=event.getX()-downX, dy=event.getY()-downY;
                long dt=System.currentTimeMillis()-downTime;
                if(Math.abs(dx)>120 && Math.abs(dx)>Math.abs(dy)*1.2f && dt<700) {
                    if(dx<0) showItem(index+1); else showItem(index-1);
                }
                return true;
            case MotionEvent.ACTION_CANCEL: return true;
        }
        return true;
    }

    private void rotateCurrent() {
        if(!(mime.startsWith("image/") || mime.startsWith("video/"))) { notifyUser("Rotation is available for images and videos"); return; }
        mediaRotation=(mediaRotation+90f)%360f;
        View target=mime.startsWith("video/") ? video : image;
        target.animate().rotation(mediaRotation).setDuration(220).start();
    }

    private void centerVideo(int videoWidth, int videoHeight) {
        if(videoWidth<=0 || videoHeight<=0) return;
        video.post(()->{
            int maxW=video.getWidth(), maxH=video.getHeight();
            if(maxW<=0 || maxH<=0) return;
            float scale=Math.min((float)maxW/videoWidth,(float)maxH/videoHeight);
            int w=Math.max(1,Math.round(videoWidth*scale));
            int h=Math.max(1,Math.round(videoHeight*scale));
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h,Gravity.CENTER);
            video.setLayoutParams(lp);
        });
    }

    private void openPdf() {
        try {
            pdfFd=getContentResolver().openFileDescriptor(uri,"r");
            if(pdfFd==null) throw new IOException("Unable to open PDF");
            pdfRenderer=new PdfRenderer(pdfFd);
            renderPdfPage(0);
        } catch(Exception e) {
            notifyUser("Could not open PDF in Storage Explorer");
        }
    }

    private void renderPdfPage(int pageIndex) {
        if(pdfRenderer==null || pageIndex<0 || pageIndex>=pdfRenderer.getPageCount()) return;
        if(pdfPageRenderer!=null) pdfPageRenderer.close();
        pdfPageRenderer=pdfRenderer.openPage(pageIndex);
        int w=pdfPageRenderer.getWidth(), h=pdfPageRenderer.getHeight();
        float scale=Math.min(1f,Math.min(2048f/w,2048f/h));
        w=Math.max(1,Math.round(w*scale)); h=Math.max(1,Math.round(h*scale));
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        android.graphics.Matrix matrix=new android.graphics.Matrix();
        matrix.setScale(scale,scale);
        pdfPageRenderer.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        pdfPage.setImageBitmap(bitmap);
        pageText.setText((pageIndex+1)+" / "+pdfRenderer.getPageCount());
        pageText.setVisibility(pdfRenderer.getPageCount()>1?View.VISIBLE:View.GONE);
    }

    private void toggleAudio() {
        try {
            if(player==null) {
                player=new MediaPlayer();
                player.setDataSource(this,uri);
                player.prepare();
                player.start();
                ((Button)findViewById(R.id.audioButton)).setText("Pause");
                player.setOnCompletionListener(mp->((Button)findViewById(R.id.audioButton)).setText("Play"));
            } else if(player.isPlaying()) {
                player.pause(); ((Button)findViewById(R.id.audioButton)).setText("Play");
            } else {
                player.start(); ((Button)findViewById(R.id.audioButton)).setText("Pause");
            }
        } catch(Exception e) { notifyUser("Could not play audio"); }
    }

    private void shareCurrent() {
        try {
            Uri shareUri=uri;
            if("file".equalsIgnoreCase(uri.getScheme())) {
                shareUri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",new File(uri.getPath()));
            }
            Intent i=new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM,shareUri);
            i.setClipData(ClipData.newRawUri("media",shareUri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"Share"));
        } catch(Exception e) { notifyUser("Share failed"); }
    }

    private void showDetails() {
        String type=mime==null?"Unknown":mime;
        String details="Name: "+name+"\nType: "+type;
        if(mime!=null && mime.equals("application/pdf") && pdfRenderer!=null) details+="\nPages: "+pdfRenderer.getPageCount();
        else {
            try {
                android.content.res.AssetFileDescriptor afd=getContentResolver().openAssetFileDescriptor(uri,"r");
                if(afd!=null){ details+="\nSize: "+formatSize(afd.getLength()); afd.close(); }
            } catch(Exception ignored) {}
        }
        new AlertDialog.Builder(this).setTitle("Media details").setMessage(details).setPositiveButton("OK",null).show();
    }

    private void openWithOtherApp() {
        try {
            Uri shareUri=uri;
            if("file".equalsIgnoreCase(uri.getScheme())) shareUri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",new File(uri.getPath()));
            Intent i=new Intent(Intent.ACTION_VIEW,shareUri);
            i.setDataAndType(shareUri,mime==null?"*/*":mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri("media",shareUri));
            startActivity(Intent.createChooser(i,"Open with another app"));
        } catch(Exception e) { notifyUser("No compatible app found"); }
    }

    private void releasePlayback() {
        if(player!=null){ player.release(); player=null; }
        if(video!=null) video.stopPlayback();
    }

    private void closePdf() {
        if(pdfPageRenderer!=null){ pdfPageRenderer.close(); pdfPageRenderer=null; }
        if(pdfRenderer!=null){ pdfRenderer.close(); pdfRenderer=null; }
        if(pdfFd!=null){ try{pdfFd.close();}catch(Exception ignored){} pdfFd=null; }
    }

    @Override public void onBackPressed() {
        if(mediaRotation!=0f) { mediaRotation=0f; image.setRotation(0f); video.setRotation(0f); return; }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        releasePlayback(); closePdf(); super.onDestroy();
    }

    private String formatSize(long n) {
        if(n<0) return "Unknown";
        if(n<1024) return n+" B";
        if(n<1024*1024) return (n/1024)+" KB";
        if(n<1024*1024*1024) return (n/(1024*1024))+" MB";
        return (n/(1024*1024*1024))+" GB";
    }

    private void notifyUser(String message) {
        View anchor=findViewById(R.id.viewerRoot);
        if(anchor!=null) Snackbar.make(anchor,message,Snackbar.LENGTH_SHORT).show();
        else Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
    }
}
