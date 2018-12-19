/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.nativeaudio;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.newventuresoftware.waveform.WaveformView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Timer;
import java.util.TimerTask;

public class NativeAudio extends Activity
        implements ActivityCompat.OnRequestPermissionsResultCallback {


    //static final String TAG = "NativeAudio";
    private static final int AUDIO_ECHO_REQUEST = 0;

    static final int CLIP_NONE = 0;
    static final int CLIP_HELLO = 1;
    static final int CLIP_ANDROID = 2;
    static final int CLIP_SAWTOOTH = 3;
    static final int CLIP_PLAYBACK = 4;

    static String URI;
    static AssetManager assetManager;

    static boolean isPlayingAsset = false;
    static boolean isPlayingVocal = false;

    static int numChannelsUri = 0;

    private Timer timer = null;
    private int recordStartTime  = 0;
    private int recordStopTime  = 0;

    private SeekBar seekBarMusic;
    private SeekBar seekBarVocal;

    private Button buttonStart;
    private Button buttonRecord;
    private Button buttonRecordPauseResume;
    private Button buttonPlay;
    private Button buttonMix;
    private TextView textViewMixPath;
    private TextView textViewLog;
    private WaveformView waveformView;
    private int selectedBeat = 0;

    private TextView textViewStatus;
    private ListView listView;
    final Handler handler = new Handler();
    private int latency = 0;

    private String[] beats = {"Ticking_Metronome.mp3", "LovedBeat.mp3", "Yung_Kartz_-_05_-_Demon.mp3"};

    /** Called when the activity is first created. */
    @Override
    @TargetApi(17)
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.main);

        int status = ActivityCompat.checkSelfPermission(NativeAudio.this,
                Manifest.permission.RECORD_AUDIO);
        if (status != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    NativeAudio.this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    AUDIO_ECHO_REQUEST);
        }

        seekBarMusic = findViewById(R.id.seekbar_music);
        seekBarVocal = findViewById(R.id.seekbar_vocal);

        seekBarMusic.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                return true;
            }
        });
        seekBarVocal.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                return true;
            }
        });

        textViewStatus = findViewById(R.id.textview_vocal);
        seekBarMusic.setMax(100);
        seekBarVocal.setMax(100);

        textViewMixPath = findViewById(R.id.textview_mix);
        textViewLog = findViewById(R.id.textview_log);
        waveformView = findViewById(R.id.waveformView);

        textViewLog.setTextColor(Color.RED);

        listView = findViewById(R.id.listView);
        ArrayAdapter<String> itemsAdapter =
                new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, beats);
        listView.setAdapter(itemsAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                isPlayingAsset = false;
                setPlayingAssetAudioPlayer(isPlayingAsset);
                destroyTimer();

                selectedBeat = i;

                /*
                playMusic();

                buttonRecord.setVisibility(View.VISIBLE);
                buttonRecordPauseResume.setVisibility(View.VISIBLE);
                buttonPlay.setVisibility(View.GONE);
                buttonStart.setEnabled(false);
                buttonRecord.setEnabled(true);
                */

            }
        });


        boolean hasLowLatencyFeature =
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY);
        if (!hasLowLatencyFeature){
            AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
            try{
                Method m = am.getClass().getMethod("getOutputLatency", int.class);
                latency = (Integer)m.invoke(am, AudioManager.STREAM_MUSIC);

                textViewLog.setText("AudioManager.STREAM_MUSIC LATENCY: " + latency);

            }catch(Exception e){
            }

            // Multi latency by 5 for input/output delay
            latency *= 5;
            //latency = 450 + latency; // Not good.
        }

        assetManager = getAssets();

        // initialize native audio system
        createEngine();

        int sampleRate = 0;
        int bufSize = 0;
        /*
         * retrieve fast audio path sample rate and buf size; if we have it, we pass to native
         * side to create a player with fast audio enabled [ fast audio == low latency audio ];
         * IF we do not have a fast audio path, we pass 0 for sampleRate, which will force native
         * side to pick up the 8Khz sample rate.
         */
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            String nativeParam = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            //String log = "PROPERTY_OUTPUT_SAMPLE_RATE: " + Integer.parseInt(nativeParam) + "\n";

            sampleRate = 44100;//Integer.parseInt(nativeParam);
            nativeParam = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            bufSize = Integer.parseInt(nativeParam);

            //log += "PROPERTY_OUTPUT_FRAMES_PER_BUFFER: " + Integer.parseInt(nativeParam) + "\n";

            //textViewLog.setText(log);

        }
        createBufferQueueAudioPlayer(sampleRate, bufSize);


        /*
        ((Button) findViewById(R.id.embedded_soundtrack)).setOnClickListener(new OnClickListener() {
            boolean created = false;
            public void onClick(View view) {

                destroyTimer();
                if (!created) {
                    created = createAssetAudioPlayer(assetManager, "Beats-bongo.mp3");
                }
                if (created) {
                    isPlayingAsset = !isPlayingAsset;
                    setPlayingAssetAudioPlayer(isPlayingAsset);

                    createTimer();
                }
            }
        });
        */

        buttonStart = (Button) findViewById(R.id.start);
        buttonStart.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {

                // Play music
                playMusic();

                buttonRecord.setVisibility(View.VISIBLE);
                buttonRecordPauseResume.setVisibility(View.VISIBLE);
                buttonPlay.setVisibility(View.GONE);
                buttonStart.setEnabled(false);
                buttonRecord.setEnabled(true);

                // Wait until player start
                while( getCurrentPositionAssetAudioPlayer() < 0) {

                }

                recordAudio();
            }
        });





        buttonRecord = (Button) findViewById(R.id.record);
        buttonRecord.setEnabled(false);
        buttonRecord.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                int status = ActivityCompat.checkSelfPermission(NativeAudio.this,
                        Manifest.permission.RECORD_AUDIO);
                if (status != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            NativeAudio.this,
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            AUDIO_ECHO_REQUEST);
                    return;
                }

                recordAudio();

            }
        });

        buttonRecordPauseResume = (Button) findViewById(R.id.recordpauseresume);
        //buttonRecordPauseResume.setEnabled(false);
        buttonRecordPauseResume.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                if (isrecording){
                    if (!isrecording_paused){
                        pauseRecording();
                        buttonRecordPauseResume.setText("RESUME");
                    }
                    else{
                        resumeRecording();
                        buttonRecordPauseResume.setText("PAUSE");
                    }

                    isrecording_paused = !isrecording_paused;
                }

            }
        });

        buttonPlay = (Button) findViewById(R.id.playback);
        buttonPlay.setVisibility(View.GONE);
        buttonPlay.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                // ignore the return value

                if (!isPlayingAsset) {
                    // Play background music

                    buttonStart.setEnabled(false);

                    playMusic();

                    textViewStatus.setText("VOCAL - WAITING....");
                    textViewStatus.setTextColor(Color.BLUE);

                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            isPlayingVocal = true;
                            selectClip(CLIP_PLAYBACK, 0);

                            textViewStatus.setText("VOCAL - PLAYING....");
                            textViewStatus.setTextColor(Color.GREEN);


                        }
                    }, recordStartTime);


                    isPlayingAsset = true;


                    // Test
                    /*
                    isPlayingAsset = true;
                    isPlayingVocal = true;
                    selectClip(CLIP_PLAYBACK, 1);
                    */
                    buttonRecord.setVisibility(View.GONE);
                    buttonRecordPauseResume.setVisibility(View.GONE);
                    buttonPlay.setText("STOP PLAYBACK");

                }
                else{
                    //handlerStop.removeCallbacksAndMessages(null);
                    handler.removeCallbacksAndMessages(null);

                    isPlayingAsset = false;
                    setPlayingAssetAudioPlayer(isPlayingAsset);
                    destroyTimer();

                    isPlayingVocal = false;

                    // Stop recording player
                    stopClip();

                    buttonRecord.setVisibility(View.VISIBLE);
                    buttonRecordPauseResume.setVisibility(View.VISIBLE);
                    buttonPlay.setVisibility(View.GONE);
                    buttonPlay.setText("PLAYBACK");

                    buttonStart.setEnabled(true);

                    // Reset status
//
//                    shutdown();
//
//                    // initialize native audio system
//                    createEngine();
//
//                    int sampleRate = 0;
//                    int bufSize = 0;
//                    /*
//                     * retrieve fast audio path sample rate and buf size; if we have it, we pass to native
//                     * side to create a player with fast audio enabled [ fast audio == low latency audio ];
//                     * IF we do not have a fast audio path, we pass 0 for sampleRate, which will force native
//                     * side to pick up the 8Khz sample rate.
//                     */
//                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
//                        AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
//                        String nativeParam = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
//                        sampleRate = Integer.parseInt(nativeParam);
//                        nativeParam = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
//                        bufSize = Integer.parseInt(nativeParam);
//                    }
//                    createBufferQueueAudioPlayer(sampleRate, bufSize);
//
//                    audioCreated = false;

                    //playMusic();
                    recordStopTime = 0;
                    recordStartTime = 0;
                    seekBarMusic.setProgress(0);
                    seekBarVocal.setProgress(0);
                    textViewStatus.setText("VOCAL");
                    textViewStatus.setTextColor(Color.BLACK);
                }


            }
        });

        buttonMix = (Button) findViewById(R.id.mix);
        buttonMix.setEnabled(false);
        buttonMix.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {

                // Stop music
                isPlayingAsset = false;
                setPlayingAssetAudioPlayer(isPlayingAsset);
                destroyTimer();

                mixAudio();

            }
        });

        Button test1 = (Button) findViewById(R.id.test1);
        test1.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                short[] recordedData = recordedData();
                textViewLog.setText("total bytes recorded: " + recordedData.length);
            }
        });


        Button test2 = (Button) findViewById(R.id.test2);
        test2.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                double duration = recordedDuration();

                textViewLog.setText("Recorded duration: " + duration + " miliseconds");
            }
        });

    }

    private boolean audioCreated = false;
    private void recordAudio(){

        if (!audioCreated) {
            audioCreated = createAudioRecorder();
            if (!audioCreated){
                textViewLog.setText("Cannot create audio recorder");
                textViewLog.setTextColor(Color.RED);
            }
        }
        if (audioCreated) {
            if (!isrecording) {
                recordStartTime = getCurrentPositionAssetAudioPlayer() + latency;
                Log.i("TAG", String.format("########## START recording at: %d", recordStartTime));

                startRecording();

                listView.setEnabled(false);
                buttonRecord.setText("STOP RECORDING");
                buttonPlay.setVisibility(View.GONE);

                textViewStatus.setText("VOCAL - RECORDING....");
                textViewStatus.setTextColor(Color.RED);
            }
            else {
                stopRecording();

                // Save to test

                try {
                    int sampleRate = 22050;

                    String path = Environment.getExternalStorageDirectory().getPath() + "/VocalRecorder";
                    File fileParent = new File(path);
                    if (!fileParent.exists()) {
                        fileParent.mkdir();
                    }

                    final File file = new File(fileParent.getPath() + "/vocal.wav");
                    int numChannels = 1;
                    short recordedData[] = recordedData();

                    // Calculate the number of frames required for specified duration
                    long numFrames = recordedData.length/2;

                    // Create a wav file with the name specified as the first argument
                    WavFile wavFile = WavFile.newWavFile(file, numChannels, numFrames, 16, sampleRate);
                    long[] buffer = new long[recordedData.length];
                    for (int i = 0; i < recordedData.length; i ++){
                        buffer[i] = recordedData[i];
                    }
                    wavFile.writeFrames(buffer, buffer.length/2);
                    wavFile.close();

                    Toast.makeText(this, "Vocal saved to: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                }
                catch (Exception e){
                    e.printStackTrace();
                }
                //

                recordStopTime = getCurrentPositionAssetAudioPlayer() + latency;
                // Stop music player
                isPlayingAsset = false;
                setPlayingAssetAudioPlayer(isPlayingAsset);
                destroyTimer();


                buttonRecord.setText("RECORD");
                buttonRecord.setVisibility(View.GONE);
                buttonRecordPauseResume.setVisibility(View.GONE);
                buttonPlay.setVisibility(View.VISIBLE);

                textViewStatus.setText("VOCAL");
                textViewStatus.setTextColor(Color.BLACK);

                buttonMix.setEnabled(true);

                buttonStart.setEnabled(true);
                listView.setEnabled(true);

                /*
                final short [] data = recordedData();

                wavIndex = 0;

                final Timer timer = new Timer();
                timer.scheduleAtFixedRate(new TimerTask() {
                    @Override
                    public void run() {
                        short [] sub = new short[2000];
                        System.arraycopy(data, wavIndex, sub, 0, 2000);
                        waveformView.setSamples(sub);

                        wavIndex += 2000;
                        if (wavIndex > data.length/4){
                            timer.cancel();
                            timer.purge();
                        }
                    }
                }, 0, 40);
                */

            }

            isrecording = !isrecording;
        }
    }

    private int wavIndex = 0;
    private void playMusic(){
        created = createAssetAudioPlayer(assetManager, beats[selectedBeat]);

        if (created) {
            isPlayingAsset = !isPlayingAsset;
            setPlayingAssetAudioPlayer(isPlayingAsset);

            createTimer();
        }
    }

    private void destroyTimer(){
        if (timer != null){
            timer.cancel();
            timer = null;
        }
    }

    private void createTimer(){
        destroyTimer();

        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        int position = getCurrentPositionAssetAudioPlayer();
                        int duration = getDurationAssetAudioPlayer();

                        //Log.i("TAG", String.format("########## playing: %d/%d", position, duration));
                        int progress = (int)(position*100/duration);
                        seekBarMusic.setProgress(progress);

                        if (isrecording || isPlayingVocal){
                            seekBarVocal.setProgress(progress);
                        }
                        else{

                            int progress2 = (int)(recordStartTime*100/duration);
                            seekBarVocal.setProgress(progress2);
                        }

                        if ((recordStopTime > 0) && (position >= recordStopTime)){
                            if (isPlayingVocal) {
                                isPlayingVocal = false;
                                stopClip();

                                // Keep slider on the end
                                recordStartTime = recordStopTime;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        textViewStatus.setText("VOCAL - DONE");
                                        textViewStatus.setTextColor(Color.BLACK);

                                    }
                                });

                            }
                        }

                        if (isrecording && (progress > duration - 200)){
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    recordAudio();
                                }
                            });
                        }
                    }
                });

            }
        }, 0, 500);
    }
    // Single out recording for run-permission needs
    static boolean created = false;
    static boolean isrecording  = false;
    static boolean isrecording_paused  = false;


    /** Called when the activity is about to be destroyed. */
    @Override
    protected void onPause()
    {
        // turn off all audio
        //selectClip(CLIP_NONE, 0);
        destroyTimer();
        stopClip();
        isPlayingVocal = false;
        isPlayingAsset = false;

        setPlayingAssetAudioPlayer(false);
        setPlayingUriAudioPlayer(false);

        super.onPause();
    }

    /** Called when the activity is about to be destroyed. */
    @Override
    protected void onDestroy()
    {
        shutdown();
        super.onDestroy();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        if (requestCode == 1000){
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED){
                mixAudio();
            }
        }
        else {
            /*
             * if any permission failed, the sample could not play
             */
            if (AUDIO_ECHO_REQUEST != requestCode) {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
                return;
            }

            if (grantResults.length != 1 ||
                    grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                /*
                 * When user denied the permission, throw a Toast to prompt that RECORD_AUDIO
                 * is necessary; on UI, we display the current status as permission was denied so
                 * user know what is going on.
                 * This application go back to the original state: it behaves as if the button
                 * was not clicked. The assumption is that user will re-click the "start" button
                 * (to retry), or shutdown the app in normal way.
                 */
                Toast.makeText(getApplicationContext(),
                        getString(R.string.NeedRecordAudioPermission),
                        Toast.LENGTH_SHORT)
                        .show();
                return;
            }

            // The callback runs on app's thread, so we are safe to resume the action
            //recordAudio();
        }
    }


    private final String LOG_TAG = "#########";
    private final int TIMEOUT_US = 100;


    private void mixAudio(){
        try {
            if (!(ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) ||
                    !(ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED))
            {
                // Show rationale and request permission.
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        1000);

            }
            else {

                buttonMix.setEnabled(false);
                buttonMix.setText("MIXING....");
                textViewMixPath.setText("");
                buttonPlay.setEnabled(false);
                buttonRecord.setEnabled(false);

                buttonStart.setEnabled(false);
                listView.setEnabled(false);

                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {

                        try{
//final File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) + "/" + "mix.wav");
                            //String baseDir = Environment.getExternalStorageDirectory().getAbsolutePath();
                            //File file = new File(baseDir  + "/mix.wav");

                            String path = Environment.getExternalStorageDirectory().getPath() + "/VocalRecorder";
                            File fileParent = new File(path);
                            if (!fileParent.exists()){
                                fileParent.mkdir();
                            }

                            final File file = new File(fileParent.getPath()  + "/mix.wav");

                            //String author = getApplicationContext().getPackageName() + ".provider";
                            //Uri videoUri = FileProvider.get(this, author, mediaFile);

                            //final File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) + "/" + "mix.wav");

                            //MediaMuxer muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                            String beat = beats[selectedBeat];
                            //beat = beat.replace(".wav", ".mp3");

                            AssetFileDescriptor afd = getAssets().openFd(beat);

                            MediaCodec codec = null;
                            //ByteBuffer outputBuffer;
                            //short[] data; // data for the AudioTrack playback
                            //int outputBufferIndex = -1;


                            MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
                            mediaMetadataRetriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                            String durationStr = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            final long duration = Long.parseLong(durationStr);

                            MediaExtractor extractor = new MediaExtractor();
                            extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                            // right now I am pointing to a URI but I have tested that both will
                            // play the media file using MediaPlayer

                            int sampleRate = 0;
                            int numChannels = 0;
                            int dstIndex = -1;
                            int numTracks = extractor.getTrackCount(); //This says 1
                            for (int i = 0; i < numTracks; ++i) { // so this will just run once
                                MediaFormat format = extractor.getTrackFormat(i); // getting info so it looks good so far
                                String mime = format.getString(MediaFormat.KEY_MIME); // "audio/mpeg"

                                if (mime.startsWith("audio/")) {
                                    extractor.selectTrack(i);
                                    codec = MediaCodec.createDecoderByType(mime);
                                    codec.configure(format, null, null, 0);

                                    //format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AMR_NB);
                                    //dstIndex = muxer.addTrack(format);

                                    //writer.setFrameRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                                    //writer.setSamplesPerFrame(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                                    //writer.setBitsPerSample(16);

                                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                                    numChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

                                    break;
                                }

                            }

                            // Calculate the number of frames required for specified duration
                            long numFrames = (long)(duration * sampleRate/1000);

                            // Create a wav file with the name specified as the first argument
                            WavFile wavFile = WavFile.newWavFile(file, numChannels, numFrames, 16, sampleRate);


                            if (codec == null) {
                                throw new IllegalArgumentException("No decoder for file format");
                            }

                            //ByteBuffer[] inputBuffers = decoder.getInputBuffers();
                            //ByteBuffer[] outputBuffers = decoder.getOutputBuffers();



            /*
            Boolean eosReceived = false;
            while (!eosReceived) {
                int inIndex = decoder.dequeueInputBuffer(1000);
                if (inIndex >= 0) {
                    ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        // We shouldn't stop the playback at this point, just pass the EOS
                        // flag to mDecoder, we will get it again from the
                        // dequeueOutputBuffer
                        Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }

                    int outIndex = decoder.dequeueOutputBuffer(info, 1000);
                    switch (outIndex) {
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
                            //outputBuffers = decoder.getOutputBuffers();
                            break;

                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            MediaFormat format = decoder.getOutputFormat();
                            Log.d("DecodeActivity", "New format " + format);
                            //audioTrack.setPlaybackRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));

                            break;
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                            break;

                        default:
                            ByteBuffer outBuffer = decoder.getOutputBuffer(outIndex);
                            Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + outBuffer);

                            final byte[] chunk = new byte[info.size];
                            outBuffer.get(chunk); // Read the buffer all at once
                            outBuffer.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME BUFFER BAD THINGS WILL HAPPEN

                            //audioTrack.write(chunk, info.offset, info.offset + info.size); // AudioTrack write data
                            decoder.releaseOutputBuffer(outIndex, false);
                            break;
                    }

                    // All decoded frames have been rendered, we can stop playing now
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                        break;
                    }
                }
            }
            */


                            short recordedData[] = recordedData();
                            int recordMixStartIndex = -1;

                            //muxer.start();

                            codec.start();
                            Boolean sawInputEOS = false;

                            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                            MediaCodec.BufferInfo infoMux = new MediaCodec.BufferInfo();

                            int count  = 0;

                            while (!sawInputEOS) {
                                int inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                                Log.i(LOG_TAG, "inputBufIndex : " + inputBufIndex);

                                if (inputBufIndex >= 0) {
                                    ByteBuffer dstBuf = codec.getInputBuffer(inputBufIndex);

                                    int sampleSize = extractor.readSampleData(dstBuf, 0);
                                    Log.i(LOG_TAG, "sampleSize : " + sampleSize);
                                    long presentationTimeUs = 0;
                                    if (sampleSize < 0) {
                                        Log.i(LOG_TAG, "Saw input end of stream!");
                                        sawInputEOS = true;
                                        sampleSize = 0;
                                    } else {
                                        presentationTimeUs = extractor.getSampleTime();
                                        Log.i(LOG_TAG, "presentationTimeUs " + presentationTimeUs);
                                    }

                                    codec.queueInputBuffer(inputBufIndex,
                                            0, //offset
                                            sampleSize,
                                            presentationTimeUs,
                                            sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                                    if (!sawInputEOS) {
                                        Log.i(LOG_TAG, "extractor.advance()");
                                        extractor.advance();

                                    }
                                }


                                final int res = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                                if (res >= 0) {
                                    int outputBufIndex = res;
                                    ByteBuffer buf = codec.getOutputBuffer(outputBufIndex);

                                    //final byte[] chunk = new byte[info.size];
                                    //buf.get(chunk); // Read the buffer all at once

                                    short[] shortArray = new short[info.size/2];
                                    buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray);

                                    buf.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME BUFFER BAD THINGS WILL HAPPEN



                                    if (shortArray.length > 0) {
                                        //mAudioTrack.write(chunk, 0, chunk.length);
                                        //infoMux.presentationTimeUs = info.presentationTimeUs;
                                        //infoMux.flags = info.flags;

                                        //muxer.writeSampleData(dstIndex, ByteBuffer.wrap(chunk),
                                        //        infoMux);

                                        long []longData = new long[shortArray.length];

                                        // Merge data with vocal

                                        // Calculate the time
                                        final long bufferTimer = info.presentationTimeUs/1000;

                                        int vocalCount = 0;

                                        for (int i = 0; i < shortArray.length; i ++) {
                                            //writer.writeShortLittle(shortArray[i]);

                                            long offsetTime = i*1000/(sampleRate*2); // 2 channels
                                            Boolean mixed = false;
                                            if ((offsetTime + bufferTimer > recordStartTime) && (offsetTime + bufferTimer <= recordStopTime + 500)){
                                                if (recordMixStartIndex == -1){
                                                    recordMixStartIndex = 0;
                                                }

                                                if (recordMixStartIndex < recordedData.length){
                                                    //Log.i("TAG", "############ mix record data: " + recordMixStartIndex);

                                                    longData[i] = TPMixSamples((int)(recordedData[recordMixStartIndex]), (int)shortArray[i]/3);
//                                                    if (recordedData[recordMixStartIndex] > shortArray[i]){
//                                                        longData[i] = recordedData[recordMixStartIndex];
//                                                    }
//                                                    else{
//                                                        longData[i] = recordedData[recordMixStartIndex]; //shortArray[i];
//                                                    }
                                                    //longData[i] = Math.max(recordedData[recordMixStartIndex]/2, shortArray[i]);
                                                    if (vocalCount >= 3) {
                                                        recordMixStartIndex++;
                                                        vocalCount = 0;
                                                    }
                                                    else{
                                                        vocalCount ++;
                                                    }
                                                    mixed = true;
                                                }
                                            }
                                            else {
                                                // All done, set sawInputEOS to stop mixing
                                                if (bufferTimer > recordStopTime + 500){
                                                    sawInputEOS = true;
                                                }
                                            }

                                            if (!mixed) {
                                                longData[i] = shortArray[i];
                                            }
                                        }


                                        Log.i("TAG", "############ write frames: " + longData.length/2);

                                        wavFile.writeFrames(longData, longData.length/2);

                                        count ++;
                                        if (count % 5 == 0){
                                            runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    long percent = bufferTimer*100/duration;
                                                    buttonMix.setText("MIXING..." + percent + "%");
                                                }
                                            });
                                        }

                                    }
                                    codec.releaseOutputBuffer(outputBufIndex, false /* render */);

                                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        sawInputEOS = true;
                                    }
                                } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                                    //codecOutputBuffers = codec.getOutputBuffers();
                                } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    final MediaFormat oformat = codec.getOutputFormat();
                                    Log.d(LOG_TAG, "Output format has changed to " + oformat);
                                    //mAudioTrack.setPlaybackRate(oformat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                                }


                            }


                            // Close the wavFile
                            wavFile.close();
                            //                muxer.stop();
                            //                muxer.release();

                            codec.stop();
                            codec.release();
                            extractor.release();

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    buttonMix.setText("MIX DONE");
                                    buttonPlay.setEnabled(true);
                                    buttonRecord.setEnabled(true);
                                    textViewMixPath.setText(file.getPath());
                                    buttonStart.setEnabled(true);
                                    listView.setEnabled(true);
                                }
                            });
                        }
                        catch (Exception e){

                        }
                    }
                });

                thread.start();


            }
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    private final int INT16_MIN = - 32768;
    private final int INT16_MAX = 32767;


    private long TPMixSamples(int a, int b) {
        if (a > INT16_MAX) {a = INT16_MAX;}
        if (a < INT16_MIN) {a = INT16_MIN;}

        return
                // If both samples are negative, mixed signal must have an amplitude between the lesser of A and B, and the minimum permissible negative amplitude
                a < 0 && b < 0 ?
                        ((int)a + (int)b) - (((int)a * (int)b)/INT16_MIN) :

                        // If both samples are positive, mixed signal must have an amplitude between the greater of A and B, and the maximum permissible positive amplitude
                        ( a > 0 && b > 0 ?
                                ((int)a + (int)b) - (((int)a * (int)b)/INT16_MAX)

                                // If samples are on opposite sides of the 0-crossing, mixed signal should reflect that samples cancel each other out somewhat
                                :
                                a + b);
    }


/*
 private long TPMixSamples(int a, int b) {
     if (a > INT16_MAX) a = INT16_MAX;
     if (a < INT16_MIN) a = INT16_MIN;

        float s1 = (float)a/INT16_MAX;
        float s2 = (float)b/INT16_MAX;

        float c = (s1 + s2) - (s1 * s2);

        return (long)(c*INT16_MAX);
    }
*/


    /** Native methods, implemented in jni folder */
    public static native void createEngine();
    public static native void createBufferQueueAudioPlayer(int sampleRate, int samplesPerBuf);

    /////
    public static native boolean createAssetAudioPlayer(AssetManager assetManager, String filename);
    // true == PLAYING, false == PAUSED
    public static native void setPlayingAssetAudioPlayer(boolean isPlaying);

    public static native int getDurationAssetAudioPlayer();
    public static native int getCurrentPositionAssetAudioPlayer();

    //////
    public static native boolean createUriAudioPlayer(String uri);
    public static native void setPlayingUriAudioPlayer(boolean isPlaying);
    public static native void setLoopingUriAudioPlayer(boolean isLooping);
    public static native void setChannelMuteUriAudioPlayer(int chan, boolean mute);
    public static native void setChannelSoloUriAudioPlayer(int chan, boolean solo);
    public static native int getNumChannelsUriAudioPlayer();
    public static native void setVolumeUriAudioPlayer(int millibel);
    public static native void setMuteUriAudioPlayer(boolean mute);
    public static native void enableStereoPositionUriAudioPlayer(boolean enable);
    public static native void setStereoPositionUriAudioPlayer(int permille);
    public static native boolean selectClip(int which, int count);
    public static native  void stopClip();
    public static native boolean enableReverb(boolean enabled);
    public static native boolean createAudioRecorder();
    public static native void startRecording();
    public static native void stopRecording();
    public static native void pauseRecording();
    public static native void resumeRecording();
    public static native short[] recordedData();
    public static native double recordedDuration();
    public static native void shutdown();

    /** Load jni .so on initialization */
    static {
        System.loadLibrary("native-audio-jni");
    }

}
