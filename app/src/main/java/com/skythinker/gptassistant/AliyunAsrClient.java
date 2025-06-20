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
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context;
        this.apiKey = apiKey;
        Log.d(TAG, "AliyunAsrClient initialized with apiKey: " + (apiKey != null && !apiKey.isEmpty() ? "***" : "null/empty"));
    }
    
    @Override
    public void startRecognize() {
        if (callback == null) {
            Log.e(TAG, "Callback is null, cannot start recognition");
            return;
        }
        
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
        
        // Create RecognitionParam, pass the Flowable<ByteBuffer> to audioFrames parameter
        RecognitionParam param = RecognitionParam.builder()
                .model("paraformer-realtime-v2")
                .format("pcm")
                .sampleRate(16000)
                .apiKey(apiKey) // set your apikey
                .parameter("enable_punctuation_prediction", true)
                .parameter("enable_inverse_text_normalization", true)
                .parameter("disfluency", false)
                .build();
        
        // Stream call interface for streaming audio to recognizer
        recognitionDisposable = recognizer.streamCall(param, audioSource)
                .subscribe(
                    result -> {
                        // Subscribe to the output result
                        Log.d(TAG, "Recognition result: " + result.toString());
                        
                        if (result != null && result.getSentence() != null) {
                            String text = result.getSentence().getText();
                            if (text != null && !text.isEmpty()) {
                                if (result.isSentenceEnd()) {
                                    Log.d(TAG, "Final Result: " + text);
                                    if (callback != null) {
                                        callback.onResult(text);
                                        if (autoStop) {
                                            callback.onAutoStop();
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "Intermediate Result: " + text);
                                    if (callback != null) {
                                        callback.onResult(text);
                                    }
                                }
                            }
                        }
                    },
                    error -> {
                        Log.e(TAG, "Recognition error", error);
                        if (callback != null) {
                            callback.onError("阿里云语音识别错误：" + error.getMessage());
                        }
                    },
                    () -> {
                        Log.d(TAG, "Recognition completed");
                        if (autoStop && callback != null) {
                            callback.onAutoStop();
                        }
                    }
                );
        
        Log.d(TAG, "Recognition started successfully");
    }
    
    private Flowable<ByteBuffer> createAudioSource() {
        return Flowable.create(
            emitter -> {
                try {
                    audioRecord.startRecording();
                    Log.d(TAG, "Audio recording started");
                    
                    byte[] audioBuffer = new byte[1024];
                    
                    while (!shouldExit[0]) {
                        int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                        if (bytesRead > 0) {
                            // Create a new ByteBuffer and copy the read data
                            ByteBuffer buffer = ByteBuffer.allocate(bytesRead);
                            buffer.put(audioBuffer, 0, bytesRead);
                            buffer.flip(); // Prepare for reading
                            emitter.onNext(buffer);
                            
                            // Small delay to control streaming rate
                            Thread.sleep(20);
                        } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || 
                                   bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                            Log.e(TAG, "AudioRecord error: " + bytesRead);
                            emitter.onError(new RuntimeException("AudioRecord error: " + bytesRead));
                            break;
                        }
                        
                        synchronized (exitFlag) {
                            if (shouldExit[0]) {
                                emitter.onComplete();
                                break;
                            }
                        }
                    }
                    
                    Log.d(TAG, "Audio streaming completed");
                    emitter.onComplete();
                    
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
        
        // Signal to stop the audio recording loop
        synchronized (exitFlag) {
            shouldExit[0] = true;
            exitFlag.notifyAll();
        }
        
        // Dispose of the recognition stream
        if (recognitionDisposable != null && !recognitionDisposable.isDisposed()) {
            recognitionDisposable.dispose();
            recognitionDisposable = null;
        }
        
        // Stop and release audio recording resources
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
                audioRecord = null;
                Log.d(TAG, "Audio recording stopped and released");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio record", e);
            }
        }
        
        // Interrupt and clean up the recording thread
        if (recordingThread != null) {
            recordingThread.interrupt();
            try {
                recordingThread.join(1000); // Wait up to 1 second for thread to finish
            } catch (InterruptedException e) {
                Log.w(TAG, "Thread join interrupted", e);
            }
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