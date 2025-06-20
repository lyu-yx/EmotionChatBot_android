package com.skythinker.gptassistant;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.exception.NoApiKeyException;

import java.nio.ByteBuffer;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;

public class AliyunAsrClient extends AsrClientBase {
    
    private static final String TAG = "AliyunAsrClient";
    
    private Context context;
    private IAsrCallback callback;
    private String apiKey;
    private boolean autoStop = false;
    
    // Audio recording related
    private AudioRecord audioRecord;
    private final int bufferSizeInBytes = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    private boolean[] shouldExit = {false};
    private Object exitFlag = new Object();
    private Thread recordingThread;
    private Disposable recognitionDisposable;
    
    public AliyunAsrClient(Context context, String apiKey) {
        this.context = context;
        this.apiKey = apiKey;
        Log.d(TAG, "AliyunAsrClient initialized with apiKey: " + (apiKey != null ? "***" : "null"));
    }
    
    @Override
    public void startRecognize() {
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onError("阿里云API Key未设置");
            return;
        }
        
        synchronized (exitFlag) {
            shouldExit[0] = false;
        }
        
        try {
            // Initialize AudioRecord
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC, 
                16000, 
                AudioFormat.CHANNEL_IN_MONO, 
                AudioFormat.ENCODING_PCM_16BIT, 
                bufferSizeInBytes
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                callback.onError("音频录制初始化失败");
                return;
            }
            
            // Start recording and recognition in a new thread
            recordingThread = new Thread(() -> {
                try {	
                    startRecordingAndRecognition();
                } catch (Exception e) {
                    Log.e(TAG, "Recording and recognition error", e);
                    callback.onError("语音识别错误：" + e.getMessage());
                }
            });
            recordingThread.start();
            
        } catch (Exception e) {
            Log.e(TAG, "Start recognize error", e);
            callback.onError("启动语音识别失败：" + e.getMessage());
        }
    }
    
    private void startRecordingAndRecognition() throws NoApiKeyException {
        // Create a Flowable<ByteBuffer> for streaming audio data
        Flowable<ByteBuffer> audioSource = createAudioSource();
        
        // Create speech Recognizer
        Recognition recognizer = new Recognition();
        
        // Create RecognitionParam
        RecognitionParam param = RecognitionParam.builder()
                .model("paraformer-realtime-v2")
                .format("pcm")
                .sampleRate(16000)
                .apiKey(apiKey)
                .parameter("semantic_punctuation_enabled", false)
                .build();
        
        // Stream call interface for streaming audio to recognizer
        recognitionDisposable = recognizer.streamCall(param, audioSource)
                .subscribe(
                    result -> {
                        // Subscribe to the output result
                        if (result.isSentenceEnd()) {
                            Log.d(TAG, "Final Result: " + result.getSentence().getText());
                            callback.onResult(result.getSentence().getText());
                            if (autoStop) {
                                callback.onAutoStop();
                            }
                        } else {
                            Log.d(TAG, "Intermediate Result: " + result.getSentence().getText());
                            callback.onResult(result.getSentence().getText());
                        }
                    },
                    error -> {
                        Log.e(TAG, "Recognition error", error);
                        callback.onError("识别错误：" + error.getMessage());
                    },
                    () -> {
                        Log.d(TAG, "Recognition completed");
                        if (autoStop) {
                            callback.onAutoStop();
                        }
                    }
                );
        
        Log.d(TAG, "Recognition started with requestId: " + recognizer.getLastRequestId());
    }
    
    private Flowable<ByteBuffer> createAudioSource() {
        return Flowable.create(
            emitter -> {
                try {
                    audioRecord.startRecording();
                    Log.d(TAG, "Audio recording started");
                    
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    
                    while (!shouldExit[0]) {
                        int read = audioRecord.read(buffer.array(), 0, buffer.capacity());
                        if (read > 0) {
                            buffer.limit(read);
                            emitter.onNext(buffer);
                            buffer = ByteBuffer.allocate(1024);
                            Thread.sleep(20); // Small delay to control CPU usage
                        }
                        
                        synchronized (exitFlag) {
                            if (shouldExit[0]) {
                                emitter.onComplete();
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Audio source error", e);
                    emitter.onError(e);
                }
            },
            BackpressureStrategy.BUFFER
        );
    }
    
    @Override
    public void stopRecognize() {
        Log.d(TAG, "Stopping recognition");
        synchronized (exitFlag) {
            shouldExit[0] = true;
            exitFlag.notifyAll();
        }
        
        if (recognitionDisposable != null && !recognitionDisposable.isDisposed()) {
            recognitionDisposable.dispose();
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.d(TAG, "Audio recording stopped and released");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio record", e);
            }
        }
        
        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }
    }
    
    @Override
    public void cancelRecognize() {
        Log.d(TAG, "Canceling recognition");
        stopRecognize();
    }
    
    @Override
    public void setCallback(IAsrCallback callback) {
        this.callback = callback;
    }
    
    @Override
    public void setParam(String key, Object value) {
        if ("apiKey".equals(key) && value instanceof String) {
            this.apiKey = (String) value;
            Log.d(TAG, "API Key updated");
        }
    }
    
    @Override
    public void setEnableAutoStop(boolean enable) {
        this.autoStop = enable;
        Log.d(TAG, "Auto stop enabled: " + enable);
    }
    
    @Override
    public void destroy() {
        Log.d(TAG, "Destroying AliyunAsrClient");
        stopRecognize();
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        Log.d(TAG, "API Key set: " + (apiKey != null ? "***" : "null"));
    }
} 