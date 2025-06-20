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
        
        // 确保之前的资源已释放
        stopRecognize();
        
        synchronized (exitFlag) {
            shouldExit[0] = false;
        }
        
        // 检查录音权限
        if (!checkAudioPermission()) {
            callback.onError("请授予录音权限");
            return;
        }
        
        try {
            // 尝试不同的音频配置来初始化AudioRecord
            if (!initializeAudioRecord()) {
                callback.onError("音频录制初始化失败，请检查麦克风权限或重启应用");
                return;
            }
            
            // Start recording and recognition in a new thread
            recordingThread = new Thread(() -> {
                try {	
                    startRecordingAndRecognition();
                } catch (Exception e) {
                    Log.e(TAG, "Recording and recognition error", e);
                    if (callback != null) {
                        callback.onError("语音识别错误：" + e.getMessage());
                    }
                }
            });
            recordingThread.start();
            
        } catch (Exception e) {
            Log.e(TAG, "Start recognize error", e);
            callback.onError("启动语音识别失败：" + e.getMessage());
        }
    }
    
    private boolean checkAudioPermission() {
        try {
            return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.e(TAG, "Error checking audio permission", e);
            return false;
        }
    }
    
    private boolean initializeAudioRecord() {
        // 释放之前的AudioRecord实例
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.w(TAG, "Error releasing previous AudioRecord", e);
            }
        }
        
        // 尝试不同的音频配置
        int[] sampleRates = {16000, 44100, 22050, 8000};
        int[] audioSources = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        };
        int[] channelConfigs = {
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.CHANNEL_IN_STEREO
        };
        
        for (int sampleRate : sampleRates) {
            for (int audioSource : audioSources) {
                for (int channelConfig : channelConfigs) {
                    try {
                        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
                        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                            continue;
                        }
                        
                        // 增加缓冲区大小以提高稳定性
                        bufferSize = Math.max(bufferSize, 4096);
                        
                        audioRecord = new AudioRecord(
                            audioSource,
                            sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        );
                        
                        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                            Log.d(TAG, String.format("AudioRecord initialized successfully: sampleRate=%d, audioSource=%d, channelConfig=%d, bufferSize=%d", 
                                sampleRate, audioSource, channelConfig, bufferSize));
                            return true;
                        } else {
                            audioRecord.release();
                            audioRecord = null;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, String.format("Failed to initialize AudioRecord with sampleRate=%d, audioSource=%d, channelConfig=%d: %s", 
                            sampleRate, audioSource, channelConfig, e.getMessage()));
                        if (audioRecord != null) {
                            try {
                                audioRecord.release();
                            } catch (Exception ex) {
                                // Ignore
                            }
                            audioRecord = null;
                        }
                    }
                }
            }
        }
        
        Log.e(TAG, "Failed to initialize AudioRecord with any configuration");
        return false;
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
                    // 检查AudioRecord状态
                    if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        emitter.onError(new RuntimeException("AudioRecord not properly initialized"));
                        return;
                    }
                    
                    // 启动录音，添加重试机制
                    int retryCount = 0;
                    while (retryCount < 3) {
                        try {
                            audioRecord.startRecording();
                            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                Log.d(TAG, "Audio recording started successfully");
                                break;
                            } else {
                                retryCount++;
                                if (retryCount < 3) {
                                    Log.w(TAG, "Failed to start recording, retrying... (" + retryCount + "/3)");
                                    Thread.sleep(100);
                                } else {
                                    emitter.onError(new RuntimeException("Failed to start audio recording after 3 attempts"));
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount < 3) {
                                Log.w(TAG, "Exception starting recording, retrying... (" + retryCount + "/3): " + e.getMessage());
                                Thread.sleep(100);
                            } else {
                                emitter.onError(new RuntimeException("Failed to start audio recording: " + e.getMessage()));
                                return;
                            }
                        }
                    }
                    
                    byte[] audioBuffer = new byte[1024];
                    int consecutiveErrors = 0;
                    
                    while (!shouldExit[0]) {
                        try {
                            int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                            
                            if (bytesRead > 0) {
                                consecutiveErrors = 0; // 重置错误计数
                                // Create a new ByteBuffer and copy the read data
                                ByteBuffer buffer = ByteBuffer.allocate(bytesRead);
                                buffer.put(audioBuffer, 0, bytesRead);
                                buffer.flip(); // Prepare for reading
                                emitter.onNext(buffer);
                                
                                // Small delay to control streaming rate
                                Thread.sleep(20);
                            } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || 
                                       bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                                consecutiveErrors++;
                                Log.w(TAG, "AudioRecord read error: " + bytesRead + " (consecutive errors: " + consecutiveErrors + ")");
                                
                                if (consecutiveErrors >= 5) {
                                    Log.e(TAG, "Too many consecutive AudioRecord errors, stopping");
                                    emitter.onError(new RuntimeException("AudioRecord read errors: " + bytesRead));
                                    break;
                                }
                                Thread.sleep(50); // 短暂等待后重试
                            } else if (bytesRead == 0) {
                                // 没有数据，短暂等待
                                Thread.sleep(10);
                            }
                            
                            synchronized (exitFlag) {
                                if (shouldExit[0]) {
                                    emitter.onComplete();
                                    break;
                                }
                            }
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Audio recording interrupted");
                            break;
                        } catch (Exception e) {
                            consecutiveErrors++;
                            Log.w(TAG, "Exception reading audio data: " + e.getMessage() + " (consecutive errors: " + consecutiveErrors + ")");
                            
                            if (consecutiveErrors >= 5) {
                                Log.e(TAG, "Too many consecutive exceptions, stopping");
                                emitter.onError(e);
                                break;
                            }
                            Thread.sleep(50);
                        }
                    }
                    
                    Log.d(TAG, "Audio streaming completed");
                    if (!emitter.isCancelled()) {
                        emitter.onComplete();
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Audio source error", e);
                    if (!emitter.isCancelled()) {
                        emitter.onError(e);
                    }
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
        try {
            if (recognitionDisposable != null && !recognitionDisposable.isDisposed()) {
                recognitionDisposable.dispose();
                recognitionDisposable = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error disposing recognition stream", e);
        }
        
        // Interrupt and clean up the recording thread first
        if (recordingThread != null) {
            try {
                recordingThread.interrupt();
                recordingThread.join(2000); // Wait up to 2 seconds for thread to finish
            } catch (InterruptedException e) {
                Log.w(TAG, "Thread join interrupted", e);
            } catch (Exception e) {
                Log.w(TAG, "Error stopping recording thread", e);
            }
            recordingThread = null;
        }
        
        // Stop and release audio recording resources
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error stopping audio record", e);
            }
            
            try {
                audioRecord.release();
                Log.d(TAG, "Audio recording stopped and released");
            } catch (Exception e) {
                Log.w(TAG, "Error releasing audio record", e);
            }
            audioRecord = null;
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
        
        // 确保所有资源都被释放
        try {
            if (audioRecord != null) {
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing AudioRecord in destroy", e);
        }
        
        callback = null;
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        Log.d(TAG, "API Key set: " + (apiKey != null ? "***" : "null"));
    }
} 