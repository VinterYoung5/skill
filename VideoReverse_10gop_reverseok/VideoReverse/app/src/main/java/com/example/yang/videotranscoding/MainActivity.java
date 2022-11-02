package com.example.yang.videotranscoding;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;

import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "yangwen";
    private static final int TRANSCODE_SUCCESS = 0x1000;
    private static final int TRANSCODE_FAIL = 0x1001;
    private static final int REQUEST_EXTERNAL_STORAGE_PERMISSION = 0x10;
    private static String[] PERMISSIONS_STORAGE = {
            READ_EXTERNAL_STORAGE,
            WRITE_EXTERNAL_STORAGE,
            MOUNT_UNMOUNT_FILESYSTEMS,
            INTERNET};

    private final static int REQUEST_CODE_SELECT_VIDEO = 0x100;
    private EditText mEdit_file_path;
    private Button mBtn_select_file, mBtn_transcode;
    private RadioGroup mRadio_group_playmode;
    private TextView mText_resolution, mText_duration, mText_size, mText_transcode_time;
    private LinearLayout mLinerLayout_video_infomation;
    private SurfaceView mSurfaceView;

    private MediaExtractor mAudioExtractor, mVideoExtractor;
    private MediaCodec mDecoder, mEncoder;
    private LinkedBlockingQueue<Frame> mQueue;
    private MediaMuxer mMuxer;
    private String mUrl_dst;
    private int mAudioTrackIndex = -1, mVideoTrackIndex = -1;
    private boolean mAudioTranscodeFinish, mVideoDecodeFinish, mVideoTranscodeFinish;
    private boolean mIsMuxerStarted;
    private boolean mIsMuxerStoped = false;
    private long mTimeTranscodeStart, mTimeTranscodeEnd;
    private long startMs;
    private boolean isFirstInputBUfferAvailable = true;
    private long mVideoDuration = 0;
    private int mFramerate = 0;
    private int playmode = 0;
    private int mMaxGOPnum = 0;
    private int mGOPIndex = 0;
    private int mWidth = 0;
    private int mHeight = 0;
    private int mYUVSize = 0;
    private byte mYUVBuffer[][];
    private int mYUVBufferNum;
    private long ptsInGop[];
    private int sizeInGop[];
    private int flagInGop[];
    private static final int mMaxGopIndex = 5 * 60 * 30; // 5min, 30fps
    private long keyframepts[] = new long[mMaxGopIndex];

    public static final int playmode_transcode = 0;
    public static final int playmode_reverse_transcode = 1;
    public static final int playmode_reverse_playback = 2;
    private Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case TRANSCODE_SUCCESS:
                    mTimeTranscodeEnd = System.currentTimeMillis();
                    mText_transcode_time.setText("转码时间: " + (mTimeTranscodeEnd - mTimeTranscodeStart) + "毫秒");
                    Toast.makeText(MainActivity.this, "转码视频保存在：" + mUrl_dst, Toast.LENGTH_LONG).show();
                    mAudioTranscodeFinish = false;
                    mVideoDecodeFinish = false;
                    mVideoTranscodeFinish = false;
                    mIsMuxerStarted = false;
                    mIsMuxerStoped = false;
                    resetView();
                    fileScan(mUrl_dst);

                    break;
                case TRANSCODE_FAIL:
                    Toast.makeText(MainActivity.this, "转码失败", Toast.LENGTH_LONG).show();
                    mAudioTranscodeFinish = false;
                    mVideoDecodeFinish = false;
                    mVideoTranscodeFinish = false;
                    mIsMuxerStarted = false;
                    mIsMuxerStoped = false;
                    resetView();
                    break;
            }
            return false;
        }
    });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mEdit_file_path = findViewById(R.id.edit_file_path);
        mLinerLayout_video_infomation = findViewById(R.id.ll_video_infomation);
        mText_resolution = findViewById(R.id.text_resolution);
        mText_duration = findViewById(R.id.text_duration);
        mText_size = findViewById(R.id.text_size);
        mText_transcode_time = findViewById(R.id.text_transcode_time);

        mSurfaceView = findViewById(R.id.surface_decode_test);

        mBtn_select_file = findViewById(R.id.btn_select_file);
        mBtn_select_file.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
                intent.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
                startActivityForResult(intent, REQUEST_CODE_SELECT_VIDEO);
            }
        });

        mBtn_transcode = findViewById(R.id.btn_transcode);
        mBtn_transcode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyStoragePermissions(MainActivity.this);
            }
        });
        mRadio_group_playmode = findViewById(R.id.radio_group_playmode);

    }
    private void initTranscoder() {
        mTimeTranscodeStart = System.currentTimeMillis();
        Log.e(TAG, "initTranscoder");

        String Url_src = mEdit_file_path.getText().toString();
        //String Url_src = "/storage/emulated/0/DCIM/Camera/1.Record_gop1.mp4";
        if (Url_src.equals("")) {
            Toast.makeText(MainActivity.this, "视频路径为空", Toast.LENGTH_SHORT).show();
            resetView();
            return;
        }

        try {
            int indexOfMime = Url_src.lastIndexOf(".");
            mUrl_dst = Url_src.substring(0, indexOfMime) + "_reverse" + Url_src.substring(indexOfMime);

            mMuxer = new MediaMuxer(mUrl_dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Log.e(TAG, "mMuxer new");
            Log.e(TAG, "Url_src:" +Url_src);
            Log.e(TAG, "Url_dst:" +mUrl_dst);
            boolean haveVideoTrack = false, haveAudioTrack = false;

            mVideoExtractor = new MediaExtractor();
            mVideoExtractor.setDataSource(Url_src);
            for (int i = 0; i < mVideoExtractor.getTrackCount(); i++) {
                MediaFormat mediaFormat = mVideoExtractor.getTrackFormat(i);
                String mime = mediaFormat.getString(MediaFormat.KEY_MIME);

                if (mime.startsWith("video/")) {

                    switch (mRadio_group_playmode.getCheckedRadioButtonId()) {
                        case R.id.radio_btn_reverse_transcode:
                            playmode = playmode_reverse_transcode;
                            break;
                        case R.id.radio_btn_reverse_playback:
                            playmode = playmode_reverse_playback;
                            break;
                        default:
                            playmode = playmode_reverse_transcode;
                            break;
                    }
                    Log.e(TAG, "playmode:"+ playmode);
                    mediaFormat.setInteger("play-mode", playmode);
                    int mode = mediaFormat.getInteger("play-mode");
                    Log.e(TAG, "mode:"+ mode);

                    String encodeMime = MediaFormat.MIMETYPE_VIDEO_AVC;
                    int bitrate  = 2000000;
                    mWidth = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
                    mHeight = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    mVideoDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION);
                    mYUVSize = calc_align(mWidth,16) * calc_align(mHeight,16) * 3 / 2;
                    try {
                        mFramerate = mediaFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
                        Log.w(TAG, "key frame rate is:" + mFramerate + " .width:" + mWidth + " .height:"+ mHeight+ " .time:" + mVideoDuration);
                    }catch (Exception ignored){
                        Log.w(TAG, "get framerate failed");
                    }

                    mEncoder = MediaCodec.createEncoderByType(encodeMime);
                    MediaFormat encodeFormat = MediaFormat.createVideoFormat(encodeMime, mWidth, mHeight);
                    encodeFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);//COLOR_FormatYUV420Planar
                    encodeFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mFramerate);
                    encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                    encodeFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                    mEncoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    mEncoder.start();

                    mediaFormat.setFloat(MediaFormat.KEY_OPERATING_RATE,(float)mFramerate);
                    mDecoder = MediaCodec.createDecoderByType(mime);

                    if (playmode == playmode_reverse_playback) {
                        mDecoder.configure(mediaFormat, mSurfaceView.getHolder().getSurface(), null, 0);
                    } else {
                        mDecoder.configure(mediaFormat, null, null, 0);
                    }

                    mDecoder.start();
                    mQueue = new LinkedBlockingQueue<>();

                    mVideoExtractor.selectTrack(i);
                    haveVideoTrack = true;
                    break;
                }
            }
            /* get current stream max gop and keyframe pts */
            ByteBuffer inputBuffer = ByteBuffer.allocate(1024*1024*2);
            int sampleSize = 0;
            int i = 0;
            int sumframenum = 0;

            do{
                sampleSize = mVideoExtractor.readSampleData(inputBuffer, 0);
                i++;
                boolean isKeyFrame = (mVideoExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                if (isKeyFrame) {
                    sumframenum += i;
                    /* I PI PP */
                    mMaxGOPnum = i > mMaxGOPnum ? i : mMaxGOPnum;
                    i = 0;
                    keyframepts[mGOPIndex] = mVideoExtractor.getSampleTime();
                    mGOPIndex++;
                } else if (sampleSize < 0){ //last gop
                    sumframenum += i;
                    /* I PI PP ,last gop should add 1(I frame)*/
                    mMaxGOPnum = i + 1 > mMaxGOPnum ? i + 1 : mMaxGOPnum;
                }

                mVideoExtractor.advance();
            } while (sampleSize >= 0 && mGOPIndex < mMaxGopIndex - 1);

            if (mYUVSize > 1920 * 1088 * 3 / 2 || mMaxGOPnum > 60 || mVideoDuration > 5 * 60 * 1000 * 1000 ) { //5 * 60s
                Log.e(TAG, "error: not support. size: " + mYUVSize + " .gopnum:" + mMaxGOPnum
                +" .mVideoDuration:"+mVideoDuration+ ".sumframenum:"+ sumframenum);
                uninitTranscoder();
                handler.sendEmptyMessage(TRANSCODE_FAIL);
            } else {
                Log.e(TAG, "init .gopnum:" + mMaxGOPnum + ".sumframenum:"+ sumframenum);
            }
            mYUVBufferNum = mMaxGOPnum * 1;
            mYUVBuffer = new byte[mYUVBufferNum][mYUVSize];
            ptsInGop = new long[mYUVBufferNum];
            flagInGop = new int[mYUVBufferNum];
            sizeInGop = new int[mYUVBufferNum];
            if (haveVideoTrack) {
                new Thread(new VideoRenderRunnable()).start();
                new Thread(new VideoDecodeRunnable()).start();
                if (playmode == playmode_reverse_transcode) {
                    new Thread(new VideoEncodeRunnable()).start();
                }
            } else {
                mMuxer.start();
                Log.e(TAG, "mMuxer start");
                mIsMuxerStarted = true;
                mVideoExtractor.release();
                mVideoTranscodeFinish = true;
            }

        } catch (Exception e) {
            uninitTranscoder();
            handler.sendEmptyMessage(TRANSCODE_FAIL);
            e.printStackTrace();
        }
    }

    private boolean IsKeyYUVbyPts(long pts){
        int min = 0;
        int max = mGOPIndex;
        boolean iskey = false;
        int cnt = 20;
        while (cnt > 0) {
            if (pts == keyframepts[min] || pts == keyframepts[max] ||
                    pts == keyframepts[(min + max) / 2]) {
                iskey = true;
                break;
            } else if (pts > keyframepts[(min + max) / 2]) {
                min = (min + max) / 2 + 1;
            } else if (pts < keyframepts[(min + max) / 2]) {
                max = (min + max) / 2 - 1;
            } else {
                iskey = false;
                break;
            }

            if (max <= min){
                iskey = false;
                break;
            }
            cnt--;
        }
        return iskey;
    }

    private class VideoRenderRunnable implements Runnable {

        @Override
        public void run() {
            while (true) {
                Log.i(TAG, "VideoRenderRunnable: ");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        }
    }

    private class VideoDecodeRunnable implements Runnable {

        @Override
        public void run() {

            mVideoExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long starttime = mVideoExtractor.getSampleTime();
            if (playmode == playmode_reverse_transcode || playmode == playmode_reverse_playback) {
                mVideoExtractor.seekTo(mVideoDuration, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }

            Log.i(TAG, "starttime: " + starttime);
            boolean needSendEOS = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean isInputEOS = false;

            long dequeueOutputBufferBeginTime = 0;
            long releaseOutputBufferBeginTime = 0;

            long dequeueOutputBufferEndTime = 0;
            long releaseOutputBufferEndTime = 0;
            long dequeueInputBufferBeginTime = 0;
            boolean isFirstDequeInputBuffer = false;
            boolean isFirstReleaseOutputBuffer = false;

            int queueInputBufferCount = 0;
            int dequeueOutputBufferCount = 0;
            int dequeueInputBufferCount = 0;
            long lastKeyframePts = mVideoDuration + 1000;
            long lastOutframePts = lastKeyframePts;
            int eosnum = 1;
            int sendeosnum = eosnum;
            int receiveeosnum = 0;

            int outputBufferIndex = 0;
            int frameIndexInGOP =0;

            while (true) {
                if (false) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                if (!isInputEOS) {
                    int inputBufferIndex = mDecoder.dequeueInputBuffer(0);
                    Log.i(TAG, "DQIn: " + inputBufferIndex);
                    if (inputBufferIndex >= 0) {

                        if (!isFirstDequeInputBuffer) {
                            isFirstDequeInputBuffer = true;
                            dequeueInputBufferBeginTime = System.currentTimeMillis();
                        }
                        dequeueInputBufferCount++;

                        ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            int sampleSize = mVideoExtractor.readSampleData(inputBuffer, 0);
                            if (needSendEOS == true) {
                                mDecoder.queueInputBuffer(inputBufferIndex, 0, 0,
                                        0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sendeosnum--;
                                if (sendeosnum <= 0) isInputEOS = true;

                                Log.i(TAG, "queue_in_cnt: only eos:"+sendeosnum);

                            } else  if (sampleSize < 0) {
                                //read tail gop end, then should read reverse
                                mVideoExtractor.seekTo(lastKeyframePts - 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                                mDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0);
                                Log.i(TAG, "queue_in_cnt:queue size0 :sampleSize :"+sendeosnum);
                            } else {
                                int flags = mVideoExtractor.getSampleFlags();
                                long pts = mVideoExtractor.getSampleTime();
                                boolean isKeyFrame = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;

                                if (isKeyFrame) {
                                    if (pts < lastKeyframePts) {// read seeked key frame,should read advance
                                        mDecoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, pts, flags);
                                        mVideoExtractor.advance();
                                        lastKeyframePts = pts;
                                        Log.i(TAG, "queue_in_cnt:" + queueInputBufferCount + ".inputBufferIndex:" + inputBufferIndex + ".pts: " + pts
                                                + ".size:" + sampleSize + " iskey: " + isKeyFrame + " . flag:" + flags);
                                    } else { //read PPPI key frame,should read reverse
                                        mDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0);
                                        if (lastKeyframePts - 1000 > starttime) {
                                            mVideoExtractor.seekTo(lastKeyframePts - 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

                                        } else {
                                            needSendEOS = true;
                                        }
                                        Log.i(TAG, "queue_in_cnt:" + inputBufferIndex + ".empty buffer:");
                                    }
                                } else {
                                    mDecoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, pts, flags);
                                    mVideoExtractor.advance();
                                    Log.i(TAG, "queue_in_cnt:" + queueInputBufferCount + ".inputBufferIndex:" + inputBufferIndex + ".pts: " + pts
                                            + ".size:" + sampleSize + " iskey: " + isKeyFrame + " . flag:" + flags);
                                }
                            }
                            queueInputBufferCount++;
                        }
                    }
                }


                outputBufferIndex = mDecoder.dequeueOutputBuffer(info, 0);

                if (mQueue.size() < mYUVBufferNum && outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mDecoder.getOutputBuffer(outputBufferIndex);
                    Log.i(TAG, "get decorder: cnt"+dequeueOutputBufferCount+ " outputBufferIndex."+outputBufferIndex+". size: " + info.size + ".flag :" + info.flags);
                    if (outputBuffer != null && info.size != 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {

                        boolean iskey = IsKeyYUVbyPts(info.presentationTimeUs);
                        Log.i(TAG, "iskey: " + iskey + " .pts:" + info.presentationTimeUs);
                        if (info.presentationTimeUs < lastOutframePts && iskey) {
                            int i,size,flag;
                            long pts;
                            for (i = frameIndexInGOP - 1; i >= 0; i--){
                                pts = ptsInGop[i];
                                flag = flagInGop[i];
                                size = sizeInGop[i];
                                try {
                                    mQueue.put(new Frame(mYUVBuffer[i].clone(), pts, flag, size));
                                    Log.i(TAG, "Decoder:mQueue put index:" + i + ".flag:" + flag + ".pts:"+pts);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                            frameIndexInGOP = 0;
                        }

                        lastOutframePts = info.presentationTimeUs;
                        outputBuffer.position(info.offset);
                        outputBuffer.get(mYUVBuffer[frameIndexInGOP], 0, info.size);
                        ptsInGop[frameIndexInGOP] = info.presentationTimeUs;
                        flagInGop[frameIndexInGOP] = info.flags;
                        sizeInGop[frameIndexInGOP] = info.size;
                        frameIndexInGOP++;
                        Log.i(TAG, "out_ts:index:" + frameIndexInGOP + " .pts: " + info.presentationTimeUs + " .size:" + info.size+".flag:"+info.flags);
                    }

                    if (!isFirstReleaseOutputBuffer) {
                        isFirstReleaseOutputBuffer = true;
                        releaseOutputBufferBeginTime = System.currentTimeMillis();
                    }

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        int i,size,flag;
                        long pts;
                        for (i = frameIndexInGOP - 1; i >= 0; i--) {
                            pts = ptsInGop[i];
                            size = sizeInGop[i];
                            flag = 0;//i > 0 ? flagInGop[i] : MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                            try {
                                mQueue.put(new Frame(mYUVBuffer[i].clone(), pts, flag, size));
                                Log.i(TAG, "Decoder:mQueue put index:" + i + ".flag:" + flag + ".pts:" + pts+".queue.size:"+mQueue.size());
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        dequeueOutputBufferEndTime = System.currentTimeMillis();
                        releaseOutputBufferEndTime = System.currentTimeMillis();

                        long dequeueOutputBufferDiff = dequeueOutputBufferEndTime - dequeueOutputBufferBeginTime;
                        long releaseOutputBufferDiff = releaseOutputBufferEndTime - releaseOutputBufferBeginTime;
                        //Log.i(TAG,"DQO_count = " + dequeueOutputBufferCount + ", RO_count = " + releaseOutputBufferCount);
                        //Log.i(TAG,"DQO_diff = " + dequeueOutputBufferDiff + "ms, RO_diff = " + releaseOutputBufferDiff + "ms");
                        //Log.i(TAG,"DQO_time = " + dequeueOutputBufferDiff/dequeueOutputBufferCount
                        //        + "ms, RO_time = " + releaseOutputBufferDiff/releaseOutputBufferCount + "ms");
                        receiveeosnum++;
                        if (receiveeosnum == eosnum) {
                            Log.i(TAG,"receiveeosnum:"+receiveeosnum);
                            break;
                        }
                    }

                    mDecoder.releaseOutputBuffer(outputBufferIndex, false);
                    dequeueOutputBufferCount++;

                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newDecodeFormat = mDecoder.getOutputFormat();
                    Log.w(TAG, "Decoder output format change to: " + newDecodeFormat.toString());
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

                }

            }
            mDecoder.stop();
            mDecoder.release();
            mVideoExtractor.release();
            mVideoExtractor = null;

            mVideoDecodeFinish = true;

        }
    }

    private class VideoEncodeRunnable implements Runnable {

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean isInputEOS = false;
            Log.i(TAG, "encoder run start");

            byte[] yuv = new byte[mYUVSize];
            int writecnt = 0;
            int pullQueueCnt = 0;
            while (true) {
                if (!isInputEOS) {
                    if (!mQueue.isEmpty()) {
                        int inputBufferIndex = mEncoder.dequeueInputBuffer(0);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer inputBuffer = mEncoder.getInputBuffer(inputBufferIndex);
                            if (inputBuffer != null) {
                                Frame frame = mQueue.poll();
                                pullQueueCnt++;
                                yuv = frame.getYuv();
                                //int size = yuv.length;
                                int size = frame.getsize();
                                inputBuffer.position(0);
                                inputBuffer.put(yuv, 0, size);
                                Log.i(TAG, "mQueue.poll.queue.size:"+mQueue.size());
                                Log.i(TAG, "enc:inputBufferIndex:"+ inputBufferIndex+" .pts:"+frame.getTimeStamp()+
                                        ".flag:"+frame.getFlags()+".size "+size+".pullcnt:"+pullQueueCnt);
                                mEncoder.queueInputBuffer(inputBufferIndex, 0, size,
                                        frame.getTimeStamp(), 0);//frame.getFlags()
                            } else {
                                Log.i(TAG, "enc: input buffer is null:inputBufferIndex:"+inputBufferIndex);
                            }
                        }
                    } else if (mVideoDecodeFinish) {
                        Log.i(TAG, "enc:inputBufferIndex eos.Qsize:"+mQueue.size());
                        int inputBufferIndex = mEncoder.dequeueInputBuffer(0);
                        if (inputBufferIndex >= 0) {
                            ByteBuffer inputBuffer = mEncoder.getInputBuffer(inputBufferIndex);
                            if (inputBuffer != null) {
                                mEncoder.queueInputBuffer(inputBufferIndex, 0, 0,
                                        0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);//
                                isInputEOS = true;
                            }
                        }
                    }
                }

                int outputBufferIndex = mEncoder.dequeueOutputBuffer(info, 0);

                if (outputBufferIndex >= 0) {
                    Log.i(TAG, "outputBufferIndex:"+ outputBufferIndex+".outoutbuffersize:"+info.size+".flag:"+info.flags);
                    ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);

                    if (outputBuffer != null && info.size != 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        Log.i(TAG, "mMuxer writeSampleData.flag:"+ info.flags+" .size:"+info.size+ " .pts: " + info.presentationTimeUs);
                        info.presentationTimeUs = writecnt * 1000000 / 30;

                        writecnt++;
                        mMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, info);
                        //Log.i(TAG, "mMuxer writeSampleData.flag:"+ info.flags+" .size:"+info.size+ " .pts: " + info.presentationTimeUs);
                    }
                    mEncoder.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newEncodeFormat = mEncoder.getOutputFormat();
                    mVideoTrackIndex = mMuxer.addTrack(newEncodeFormat);
                    mMuxer.start();
                    Log.e(TAG, "mMuxer FORMAT_CHANGED start");
                    mIsMuxerStarted = true;
                    Log.i(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

                } else {
                    Log.i(TAG, "encoder:dequeue outbuf fail:" + outputBufferIndex);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "encoder :outEOSflag:"+ info.flags+".size:"+info.size);
                    break;
                }


            }
            mEncoder.stop();
            mEncoder.release();

            mVideoTranscodeFinish = true;
            mAudioTranscodeFinish = true;
            if (mAudioTranscodeFinish) {
                stopMuxer();
                handler.sendEmptyMessage(TRANSCODE_SUCCESS);
            }
        }
    }


    private class AudioTranscodeRunnable implements Runnable {

        @Override
        public void run() {
            /*
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.presentationTimeUs = 0;
            ByteBuffer buffer = ByteBuffer.allocate(100 * 1024);

            Log.i(TAG, "audio start");
            while (true) {
                if (mIsMuxerStarted) {
                    int sampleSize = mAudioExtractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        break;
                    }
                    info.offset = 0;
                    info.size = sampleSize;
                    info.flags = mAudioExtractor.getSampleFlags();
//                info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    info.presentationTimeUs = mAudioExtractor.getSampleTime();
                    mMuxer.writeSampleData(mAudioTrackIndex, buffer, info);
//                    Log.i(TAG, "Audio data: " + (float) info.presentationTimeUs / 1000000);
                    mAudioExtractor.advance();
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            mAudioExtractor.release();
            mAudioExtractor = null;

            mAudioTranscodeFinish = true;
            if (mVideoTranscodeFinish) {
                stopMuxer();
                handler.sendEmptyMessage(TRANSCODE_SUCCESS);
            }
            */
        }
    }

    private void stopMuxer() {
        Log.i(TAG, "mMuxer (mIsMuxerStarted:"+ mIsMuxerStarted +").stopMuxer");
        if (mMuxer != null && mIsMuxerStoped != true && mIsMuxerStarted == true) {
            mIsMuxerStoped = true;
            mMuxer.stop();
            //mMuxer.release();
        }
    }

    private void uninitTranscoder() {
        mQueue.clear();
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
        }
        if (mDecoder != null) {
            mDecoder.stop();
            mDecoder.release();
        }
        if (mAudioExtractor != null) {
            mAudioExtractor.release();
        }
        if (mVideoExtractor != null) {
            mVideoExtractor.release();
        }
        if (mMuxer != null) {
            Log.e(TAG, "mMuxer release");
            mMuxer.release();
        }
    }


    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_CODE_SELECT_VIDEO) {
                Uri uri = data.getData();
                if (uri != null) {
                    Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    if (cursor != null) {
                        cursor.moveToFirst();
                        String url = cursor.getString(cursor.getColumnIndex(MediaStore.Video.Media.DATA));
                        if (url != null) {
                            mEdit_file_path.setText(url);
                        }
                        long size = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.SIZE));
                        long duration = cursor.getLong(cursor.getColumnIndex(MediaStore.Video.Media.DURATION));
                        int width = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.WIDTH));
                        int height = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT));
                        mText_resolution.setText(width + "x" + height);
                        mText_duration.setText(getStrTime(duration));
                        mText_size.setText(getVideoSize(size));
                        mLinerLayout_video_infomation.setVisibility(View.VISIBLE);
                        cursor.close();
                    }
                }
            }
        }
    }

    private void checkTranscode() {
        Log.i(TAG, "checkTranscode");
        mText_transcode_time.setText("转码时间: ");
        mBtn_transcode.setText("转码中...");
        mBtn_transcode.setEnabled(false);
        mEdit_file_path.setEnabled(false);
        mBtn_select_file.setEnabled(false);
        disableRadioGroup(mRadio_group_playmode);

        if (mEdit_file_path.getText().toString().equals("")) {
            //Toast.makeText(MainActivity.this, "请输入视频路径", Toast.LENGTH_SHORT).show();
            //resetView();
            //return;
        }
        initTranscoder();
    }

    private void verifyStoragePermissions(Activity activity) {
        if (ActivityCompat.checkSelfPermission(activity, MOUNT_UNMOUNT_FILESYSTEMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE_PERMISSION);
        } else {
            checkTranscode();
        }
    }

    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_EXTERNAL_STORAGE_PERMISSION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    checkTranscode();
                } else {
                    Toast.makeText(MainActivity.this, "申请文件读取权限失败", Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    private void resetView() {
        mBtn_transcode.setText("转码");
        mBtn_transcode.setEnabled(true);
        mEdit_file_path.setEnabled(true);
        mBtn_select_file.setEnabled(true);
        enableRadioGroup(mRadio_group_playmode);
    }

    private String getVideoSize(long size) {
        String string = "0B";
        BigDecimal bigDecimal;
        DecimalFormat df = new DecimalFormat();
        if (size == 0) {
            return string;
        }
        if (size < 1024) {
            string = df.format((double) size) + "B";
        } else if (size < 1048576) {
            bigDecimal = new BigDecimal((double) size / 1024);
            string = df.format(bigDecimal.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue()) + "KB";
        } else if (size < 1073741824) {
            bigDecimal = new BigDecimal((double) size / 1048576);
            string = df.format(bigDecimal.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue()) + "MB";
        } else {
            bigDecimal = new BigDecimal((double) size / 1073741824);
            string = df.format(bigDecimal.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue()) + "GB";
        }
        return string;
    }

    private String getStrTime(long cc_time) {
        String re_StrTime;
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("ETC/GMT-8"));
        re_StrTime = sdf.format(new Date(cc_time));
        return re_StrTime;
    }

    private void disableRadioGroup(RadioGroup radioGroup) {
        for (int i = 0; i < radioGroup.getChildCount(); i++) {
            radioGroup.getChildAt(i).setEnabled(false);
        }
    }

    private void enableRadioGroup(RadioGroup radioGroup) {
        for (int i = 0; i < radioGroup.getChildCount(); i++) {
            radioGroup.getChildAt(i).setEnabled(true);
        }
    }

    private int calc_align(int n, int align) {
        return ((n + align - 1) & (~(align - 1)));
    }

    private void fileScan(String filePath) {

        Uri data = Uri.fromFile(new File(filePath));
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, data));
    }

}
