package com.yeragorla.whatsappcontentviewer;

import android.app.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.File;

public class MediaViewerActivity extends AppCompatActivity {
    private Uri uri;
    private String mime;
    private MediaPlayer player;
    private VideoView video;
    private ImageView image;
    private LinearLayout audioPanel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_media_viewer);
        image=findViewById(R.id.imageView); video=findViewById(R.id.videoView); audioPanel=findViewById(R.id.audioPanel);
        uri=getIntent().getParcelableExtra("media_uri"); mime=getIntent().getStringExtra("media_mime");
        String name=getIntent().getStringExtra("media_name");
        ((TextView)findViewById(R.id.titleText)).setText(name==null?"Media":name);
        findViewById(R.id.closeButton).setOnClickListener(v->finish());
        findViewById(R.id.openOtherButton).setOnClickListener(v->openWithOtherApp());
        if(uri==null){ toast("Media could not be opened"); finish(); return; }
        showMedia();
    }

    private void showMedia(){
        if(mime!=null && mime.startsWith("image/")){
            image.setVisibility(View.VISIBLE);
            image.setImageURI(uri);
        } else if(mime!=null && mime.startsWith("video/")){
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(uri);
            video.setMediaController(new MediaController(this));
            video.requestFocus();
            video.setOnPreparedListener(mp->video.start());
        } else if(mime!=null && mime.startsWith("audio/")){
            audioPanel.setVisibility(View.VISIBLE);
            String n=getIntent().getStringExtra("media_name"); ((TextView)findViewById(R.id.mediaName)).setText(n==null?"Audio":n);
            findViewById(R.id.audioButton).setOnClickListener(v->toggleAudio());
        } else { openWithOtherApp(); }
    }

    private void toggleAudio(){
        try{
            if(player==null){ player=new MediaPlayer(); player.setDataSource(this,uri); player.prepare(); player.start(); ((Button)findViewById(R.id.audioButton)).setText("Pause"); player.setOnCompletionListener(mp->((Button)findViewById(R.id.audioButton)).setText("Play")); }
            else if(player.isPlaying()){ player.pause(); ((Button)findViewById(R.id.audioButton)).setText("Play"); }
            else { player.start(); ((Button)findViewById(R.id.audioButton)).setText("Pause"); }
        }catch(Exception e){ toast("Could not play audio"); }
    }

    private void openWithOtherApp(){
        try{
            Uri shareUri = uri;
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                File file = new File(uri.getPath());
                shareUri = FileProvider.getUriForFile(this, getPackageName()+".fileprovider", file);
            }
            Intent i=new Intent(Intent.ACTION_VIEW,shareUri);
            i.setDataAndType(shareUri,mime==null?"*/*":mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri("media",shareUri));
            startActivity(Intent.createChooser(i,"Open with another app"));
        }catch(Exception e){toast("No compatible app found");}
    }

    @Override protected void onStop(){ super.onStop(); if(player!=null){player.release();player=null;} if(video!=null)video.stopPlayback(); }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}