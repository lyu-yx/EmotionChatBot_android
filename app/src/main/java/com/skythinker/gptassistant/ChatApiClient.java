package com.skythinker.gptassistant;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.unfbx.chatgpt.OpenAiStreamClient;
import com.unfbx.chatgpt.entity.chat.BaseChatCompletion;
import com.unfbx.chatgpt.entity.chat.BaseMessage;
import com.unfbx.chatgpt.entity.chat.ChatCompletionWithPicture;
import com.unfbx.chatgpt.entity.chat.Content;
import com.unfbx.chatgpt.entity.chat.FunctionCall;
import com.unfbx.chatgpt.entity.chat.Functions;
import com.unfbx.chatgpt.entity.chat.ImageUrl;
import com.unfbx.chatgpt.entity.chat.Message;
import com.unfbx.chatgpt.entity.chat.ChatCompletion;
import com.unfbx.chatgpt.entity.chat.MessagePicture;
import com.unfbx.chatgpt.entity.chat.Parameters;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import cn.hutool.json.JSONObject;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.internal.http2.StreamResetException;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;

import com.skythinker.gptassistant.ChatManager.ChatMessage.ChatRole;
import com.skythinker.gptassistant.ChatManager.ChatMessage;
import com.unfbx.chatgpt.entity.chat.tool.ToolCallFunction;
import com.unfbx.chatgpt.entity.chat.tool.ToolCalls;
import com.unfbx.chatgpt.entity.chat.tool.ToolChoice;
import com.unfbx.chatgpt.entity.chat.tool.Tools;
import com.unfbx.chatgpt.entity.chat.tool.ToolsFunction;
import com.unfbx.chatgpt.entity.whisper.WhisperResponse;

public class ChatApiClient {
    // 消息回调接口
    public interface OnReceiveListener {
        void onMsgReceive(String message);
        void onError(String message);
        void onFunctionCall(ArrayList<CallingFunction> functions);
        void onFinished(boolean completed);
    }

    public static class CallingFunction {
        public String toolId = "";
        public String name = "";
        public String arguments = "";
    }

    String url = "";
    String apiKey = "";
    String model = "";
    float temperature = 0.5f;
    OnReceiveListener listener = null;

    OkHttpClient httpClient = null;
    OpenAiStreamClient chatGPT = null;

    List<Tools> functions = new ArrayList<>();

    ArrayList<CallingFunction> callingFunctions = new ArrayList<>();

    boolean isReasoning = false;

    Context context = null;

    public ChatApiClient(Context context, String url, String apiKey, String model, OnReceiveListener listener) {
        this.context = context;
        this.listener = listener;
        this.model = model;
        this.temperature = GlobalDataHolder.getGptTemperature(); // 从全局设置中获取温度参数
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)  // 增加连接超时时间
            .readTimeout(120, TimeUnit.SECONDS)    // 增加读取超时时间，适应流式响应
            .writeTimeout(60, TimeUnit.SECONDS)    // 增加写入超时时间
            .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))  // 优先使用现代TLS
            .retryOnConnectionFailure(true)  // 启用连接失败重试
            .build();
        setApiInfo(url, apiKey);
    }

    // 向GPT发送消息列表
    public void sendPromptList(List<ChatMessage> promptList) {
        if(url.isEmpty() || apiKey.isEmpty() || chatGPT == null) {
            listener.onError(context.getString(R.string.text_gpt_conf_error));
            return;
        }
        
        Log.d("ChatApiClient", "=== REQUEST DEBUG INFO ===");
        Log.d("ChatApiClient", "Sending request to: " + url);
        Log.d("ChatApiClient", "Using model: " + model);
        Log.d("ChatApiClient", "API Key: " + (apiKey != null && apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey));
        Log.d("ChatApiClient", "Is Aliyun mode: " + GlobalDataHolder.getUseAliyunChat());
        Log.d("ChatApiClient", "Temperature: " + temperature);
        
        // 验证阿里云模式下的配置
        if (GlobalDataHolder.getUseAliyunChat()) {
            Log.d("ChatApiClient", "=== ALIYUN CONFIG CHECK ===");
            Log.d("ChatApiClient", "Expected URL: https://dashscope.aliyuncs.com/compatible-mode/v1/");
            Log.d("ChatApiClient", "Actual URL: " + url);
            Log.d("ChatApiClient", "URL Match: " + url.equals("https://dashscope.aliyuncs.com/compatible-mode/v1/"));
            Log.d("ChatApiClient", "API Key Format: " + (apiKey != null && apiKey.startsWith("sk-") ? "✓ Correct (sk-...)" : "✗ Wrong format"));
            Log.d("ChatApiClient", "Model: " + model);
            
            // 检查模型是否是支持的Qwen模型
            String[] supportedModels = {"qwen-turbo", "qwen-plus", "qwen-max", "qwen-long", 
                "qwen-vl-plus", "qwen-vl-max", "qwen-audio-turbo", "qwen-audio-chat",
                "qwen2.5-72b-instruct", "qwen2.5-32b-instruct", "qwen2.5-14b-instruct", 
                "qwen2.5-7b-instruct", "qwen2.5-3b-instruct", "qwen2.5-1.5b-instruct", "qwen2.5-0.5b-instruct",
                "qwen2-72b-instruct", "qwen2-57b-a14b-instruct", "qwen2-7b-instruct", 
                "qwen2-1.5b-instruct", "qwen2-0.5b-instruct",
                "qwen1.5-110b-chat", "qwen1.5-72b-chat", "qwen1.5-32b-chat", 
                "qwen1.5-14b-chat", "qwen1.5-7b-chat", "qwen1.5-4b-chat", "qwen1.5-1.8b-chat", "qwen1.5-0.5b-chat"};
            boolean isModelSupported = java.util.Arrays.asList(supportedModels).contains(model.replaceAll("\\*$", ""));
            Log.d("ChatApiClient", "Model Support: " + (isModelSupported ? "✓ Supported" : "⚠ Unknown model"));
            Log.d("ChatApiClient", "============================");
        }
        
        Log.d("ChatApiClient", "=========================");

        BaseChatCompletion chatCompletion = null;

        boolean hasAnyAtttachment = false;
        for(ChatMessage message : promptList) {
            if(message.attachments.size() > 0) {
                hasAnyAtttachment = true;
                break;
            }
        }

        if(!hasAnyAtttachment) { // 没有任何附件，使用普通content格式（兼容旧模型）
            ArrayList<Message> messageList = new ArrayList<>(); // 将消息数据转换为ChatGPT需要的格式
            for (ChatMessage message : promptList) {
                if (message.role == ChatRole.SYSTEM) {
                    messageList.add(Message.builder().role(Message.Role.SYSTEM).content(message.contentText).build());
                } else if (message.role == ChatRole.USER) {
                    messageList.add(Message.builder().role(Message.Role.USER).content(message.contentText).build());
                } else if (message.role == ChatRole.ASSISTANT) {
                    if (message.toolCalls.size() > 0) {
                        if(message.toolCalls.get(0).id != null) { // 用tool方式回复
                            ArrayList<ToolCalls> toolCallsList = new ArrayList<>();
                            for(ChatMessage.ToolCall toolCall : message.toolCalls) {
                                ToolCallFunction functionCall = ToolCallFunction.builder()
                                        .name(toolCall.functionName)
                                        .arguments(toolCall.arguments)
                                        .build();
                                ToolCalls toolCalls = ToolCalls.builder()
                                        .id(toolCall.id)
                                        .type(Tools.Type.FUNCTION.getName())
                                        .function(functionCall)
                                        .build();
                                toolCallsList.add(toolCalls);
                            }
                            messageList.add(Message.builder().role(Message.Role.ASSISTANT).toolCalls(toolCallsList).content("").build());
                        } else { // 用function方式回复（历史遗留）
                            ChatMessage.ToolCall toolCall = message.toolCalls.get(0);
                            FunctionCall functionCall = FunctionCall.builder()
                                .name(toolCall.functionName)
                                .arguments(toolCall.arguments)
                                .build();
                            messageList.add(Message.builder().role(Message.Role.ASSISTANT).functionCall(functionCall).content("").build());
                        }
                    } else {
                        messageList.add(Message.builder().role(Message.Role.ASSISTANT)
                                .content(message.contentText.replaceFirst("(?s)^<think>\\n.*?\\n</think>\\n", "")).build()); // 去除思维链内容
                    }
                } else if (message.role == ChatRole.FUNCTION) {
                    ChatMessage.ToolCall toolCall = message.toolCalls.get(0);
                    if(toolCall.id != null) { // 用tool方式回复
                        messageList.add(Message.builder().role(Message.Role.TOOL).toolCallId(toolCall.id).name(toolCall.functionName).content(toolCall.content).build());
                    } else { // 用function方式回复（历史遗留）
                        messageList.add(Message.builder().role(Message.Role.FUNCTION).name(toolCall.functionName).content(toolCall.content).build());
                    }
                }
            }

            if (!functions.isEmpty()) { // 如果有函数列表，则将函数列表传入
                chatCompletion = ChatCompletion.builder()
                        .messages(messageList)
                        .model(model.replaceAll("\\*$","")) // 去掉自定义模型结尾的*号
                        .tools(functions)
                        .toolChoice(ToolChoice.Choice.AUTO.getName())
                        .temperature(temperature)
                        .build();
            } else {
                chatCompletion = ChatCompletion.builder()
                        .messages(messageList)
                        .model(model.replaceAll("\\*$","")) // 去掉自定义模型结尾的*号
                        .temperature(temperature)
                        .build();
            }
        } else { // 含有附件，使用contentList格式
            ArrayList<MessagePicture> messageList = new ArrayList<>(); // 将消息数据转换为ChatGPT需要的格式
            for (ChatMessage message : promptList) {
                List<Content> contentList = new ArrayList<>();
                if (message.contentText != null) {
                    String contentText = message.role != ChatRole.ASSISTANT ? message.contentText :
                            message.contentText.replaceFirst("(?s)^<think>\\n.*?\\n</think>\\n", ""); // 去除思维链内容
                    contentList.add(Content.builder().type(Content.Type.TEXT.getName()).text(contentText).build());
                }
                for(ChatMessage.ToolCall toolCall : message.toolCalls) { // 处理函数调用
                    if(toolCall.content != null) {
                        contentList.add(Content.builder().type(Content.Type.TEXT.getName()).text(toolCall.content).build());
                    }
                }
                for(ChatMessage.Attachment attachment : message.attachments) { // 处理附件
                    if(attachment.type == ChatMessage.Attachment.Type.IMAGE && GlobalUtils.checkVisionSupport(model)) {
                        ImageUrl imageUrl = ImageUrl.builder().url("data:image/jpeg;base64," + attachment.content).build();
                        contentList.add(Content.builder().type(Content.Type.IMAGE_URL.getName()).imageUrl(imageUrl).build());
                    } else if(attachment.type == ChatMessage.Attachment.Type.TEXT) {
                        contentList.add(Content.builder().type(Content.Type.TEXT.getName()).text(attachment.content).build());
                    }
                }
                if (message.role == ChatRole.SYSTEM) {
                    messageList.add(MessagePicture.builder().role(Message.Role.SYSTEM).content(contentList).build());
                } else if (message.role == ChatRole.USER) {
                    messageList.add(MessagePicture.builder().role(Message.Role.USER).content(contentList).build());
                } else if (message.role == ChatRole.ASSISTANT) {
                    if (message.toolCalls.size() > 0) {
                        if(message.toolCalls.get(0).id != null) { // 用tool方式回复
                            ArrayList<ToolCalls> toolCallsList = new ArrayList<>();
                            for(ChatMessage.ToolCall toolCall : message.toolCalls) {
                                ToolCallFunction functionCall = ToolCallFunction.builder()
                                        .name(toolCall.functionName)
                                        .arguments(toolCall.arguments)
                                        .build();
                                ToolCalls toolCalls = ToolCalls.builder()
                                        .id(toolCall.id)
                                        .type(Tools.Type.FUNCTION.getName())
                                        .function(functionCall)
                                        .build();
                                toolCallsList.add(toolCalls);
                            }
                            messageList.add(MessagePicture.builder().role(Message.Role.ASSISTANT).toolCalls(toolCallsList).build());
                        } else { // 用function方式回复（历史遗留）
                            ChatMessage.ToolCall toolCall = message.toolCalls.get(0);
                            FunctionCall functionCall = FunctionCall.builder()
                                    .name(toolCall.functionName)
                                    .arguments(toolCall.arguments)
                                    .build();
                            messageList.add(MessagePicture.builder().role(Message.Role.ASSISTANT).functionCall(functionCall).build());
                        }
                    } else {
                        messageList.add(MessagePicture.builder().role(Message.Role.ASSISTANT).content(contentList).build());
                    }
                } else if (message.role == ChatRole.FUNCTION) {
                    ChatMessage.ToolCall toolCall = message.toolCalls.get(0);
                    if(toolCall.id != null) { // 用tool方式回复
                        messageList.add(MessagePicture.builder().role(Message.Role.TOOL).toolCallId(toolCall.id).name(toolCall.functionName).content(contentList).build());
                    } else { // 用function方式回复（历史遗留）
                        messageList.add(MessagePicture.builder().role(Message.Role.FUNCTION).name(toolCall.functionName).content(contentList).build());
                    }
                }
            }

            if (!functions.isEmpty()) { // 如果有函数列表，则将函数列表传入
                chatCompletion = ChatCompletionWithPicture.builder()
                        .messages(messageList)
                        .model(model.replaceAll("\\*$","")) // 去掉自定义Vision模型结尾的*号
                        .tools(functions)
                        .toolChoice(ToolChoice.Choice.AUTO.getName())
                        .temperature(temperature)
                        .build();
            } else {
                chatCompletion = ChatCompletionWithPicture.builder()
                        .messages(messageList)
                        .model(model.replaceAll("\\*$","")) // 去掉自定义Vision模型结尾的*号
                        .temperature(temperature)
                        .build();
            }
        }

        callingFunctions.clear(); // 清空当前函数调用列表

        // 记录请求详情以便调试
        Log.d("ChatApiClient", "Final request model: " + model.replaceAll("\\*$",""));
        Log.d("ChatApiClient", "Request temperature: " + temperature);
        Log.d("ChatApiClient", "Functions count: " + functions.size());
        Log.d("ChatApiClient", "Messages count: " + promptList.size());

        chatGPT.streamChatCompletion(chatCompletion, new EventSourceListener() { // GPT返回消息回调
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                Log.d("ChatApiClient", "=== CONNECTION OPENED ===");
                Log.d("ChatApiClient", "Response Code: " + response.code());
                Log.d("ChatApiClient", "Response Message: " + response.message());
                Log.d("ChatApiClient", "Is Aliyun Mode: " + GlobalDataHolder.getUseAliyunChat());
                Log.d("ChatApiClient", "========================");
            }

            @Override
            public void onEvent(EventSource eventSource, @Nullable String id, @Nullable String type, String data) {
                if(data.equals("[DONE]")){ // 回复完成
                    Log.d("ChatApiClient", "onEvent: DONE");
                    if(callingFunctions.isEmpty()) {
                        listener.onFinished(true);
                    } else {
                        listener.onFunctionCall(callingFunctions);
                    }
                } else { // 正在回复
                    Log.d("ChatApiClient", "onEvent: " + data);
                    JSONObject json = new JSONObject(data);
                    if(json.containsKey("choices") && json.getJSONArray("choices").size() > 0) {
                        JSONObject delta = ((JSONObject) json.getJSONArray("choices").get(0)).getJSONObject("delta");
                        if (delta != null) {
                            if (delta.containsKey("tool_calls")) { // GPT请求函数调用
                                JSONObject toolCall = delta.getJSONArray("tool_calls").getJSONObject(0);
                                JSONObject functionCall = toolCall.getJSONObject("function");
                                if (toolCall.containsKey("id") && functionCall.containsKey("name")) {
                                    CallingFunction callingFunction = new CallingFunction();
                                    callingFunction.toolId = toolCall.getStr("id");
                                    callingFunction.name = functionCall.getStr("name");
                                    callingFunctions.add(callingFunction);
                                }
                                if (callingFunctions.size() > 0 && functionCall.containsKey("arguments")) {
                                    callingFunctions.get(callingFunctions.size() - 1).arguments += functionCall.getStr("arguments");
                                }
                            } else if (delta.containsKey("content") && delta.getStr("content") != null) { // GPT返回普通消息
                                if (isReasoning) {
                                    isReasoning = false;
                                    listener.onMsgReceive("\n</think>\n");
                                }
                                listener.onMsgReceive(delta.getStr("content"));
                            } else if (delta.containsKey("reasoning_content") && delta.getStr("reasoning_content") != null) { // GPT返回思维链消息
                                if (!isReasoning) {
                                    isReasoning = true;
                                    listener.onMsgReceive("<think>\n");
                                }
                                listener.onMsgReceive(delta.getStr("reasoning_content"));
                            }
                        }
                    }
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                Log.d("ChatApiClient", "onClosed");
            }

            @Override
            public void onFailure(EventSource eventSource, @Nullable Throwable throwable, @Nullable Response response) {
                if(throwable != null) {
                    if(throwable instanceof StreamResetException) { // 请求被用户取消，不算错误
                        Log.d("ChatApiClient", "onFailure: Cancelled");
                        listener.onFinished(false);
                    } else {
                        String err = throwable.toString();
                        Log.e("ChatApiClient", "onFailure: " + err + "\n" + Log.getStackTraceString(throwable));
                        
                        // 针对不同类型的错误提供更友好的提示
                        if(err.contains("java.io.IOException: Canceled")) {
                            err = context.getString(R.string.text_gpt_cancel);
                        } else if(err.contains("SocketTimeoutException") || err.contains("timeout")) {
                            err = context.getString(R.string.text_gpt_timeout);
                        } else if(err.contains("UnknownHostException") || err.contains("ConnectException")) {
                            err = "网络连接失败，请检查网络设置";
                        } else if(err.contains("SSLException")) {
                            err = "SSL连接失败，请检查网络安全设置";
                        }
                        listener.onError(err);
                    }
                } else {
                    if(response != null) {
                        Log.e("ChatApiClient", "HTTP Error - Code: " + response.code() + ", Message: " + response.message());
                        
                        if(response.body() != null) {
                            try {
                                String errorBody = response.body().string();
                                Log.e("ChatApiClient", "=== ERROR RESPONSE DEBUG ===");
                                Log.e("ChatApiClient", "HTTP Code: " + response.code());
                                Log.e("ChatApiClient", "HTTP Message: " + response.message());
                                Log.e("ChatApiClient", "Response Headers: " + response.headers().toString());
                                Log.e("ChatApiClient", "Error Response Body: " + errorBody);
                                Log.e("ChatApiClient", "Is Aliyun Mode: " + GlobalDataHolder.getUseAliyunChat());
                                Log.e("ChatApiClient", "Request URL: " + url);
                                Log.e("ChatApiClient", "============================");
                                
                                // 尝试解析阿里云API错误格式
                                String err;
                                if (GlobalDataHolder.getUseAliyunChat()) {
                                    err = parseAliyunError(errorBody, response.code());
                                } else {
                                    err = "HTTP " + response.code() + ": " + response.message() + "\n" + errorBody;
                                }
                                
                                if(err.length() > 500) {
                                    err = err.substring(0, 500) + "...";
                                }
                                listener.onError(err);
                            } catch (IOException e) {
                                Log.e("ChatApiClient", "Failed to read error response body", e);
                                listener.onError("HTTP " + response.code() + ": " + response.message());
                            }
                        } else {
                            listener.onError("HTTP " + response.code() + ": " + response.message());
                        }
                    } else {
                        listener.onError(context.getString(R.string.text_gpt_unknown_error));
                    }
                }
            }
        });
    }

    // 配置API信息
    public void setApiInfo(String url, String apiKey) {
        // 根据是否使用阿里云来决定API端点和API Key
        String actualUrl = url;
        String actualApiKey = apiKey;
        
        if (GlobalDataHolder.getUseAliyunChat()) {
            // 根据阿里云官方文档，使用OpenAI兼容模式的base_url
            actualUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/";
            // 在阿里云模式下，优先使用阿里云ASR的API Key，如果没有则使用传入的API Key
            String aliyunApiKey = GlobalDataHolder.getAsrAliyunApiKey();
            if (aliyunApiKey != null && !aliyunApiKey.trim().isEmpty() && aliyunApiKey.startsWith("sk-")) {
                actualApiKey = aliyunApiKey;
                Log.d("ChatApiClient", "Using Aliyun ASR API Key for chat: " + (actualApiKey.length() > 10 ? actualApiKey.substring(0, 10) + "..." : actualApiKey));
            } else {
                // 如果阿里云ASR API Key不可用，则使用传入的API Key（应该也是阿里云格式）
                actualApiKey = apiKey;
                Log.d("ChatApiClient", "Using provided API Key for Aliyun chat: " + (actualApiKey != null && actualApiKey.length() > 10 ? actualApiKey.substring(0, 10) + "..." : "null"));
            }
            Log.d("ChatApiClient", "Using Aliyun DashScope API: " + actualUrl);
        }
        
        if(this.url.equals(actualUrl) && this.apiKey.equals(actualApiKey)) {
            return;
        }
        this.url = actualUrl;
        this.apiKey = actualApiKey;
        
        try {
            // 验证API Key格式
            if (actualApiKey == null || actualApiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("API Key不能为空");
            }
            
            if (GlobalDataHolder.getUseAliyunChat()) {
                // 阿里云API Key通常以sk-开头
                if (!actualApiKey.startsWith("sk-")) {
                    Log.e("ChatApiClient", "阿里云API Key格式错误！当前API Key: " + (actualApiKey != null && actualApiKey.length() > 10 ? actualApiKey.substring(0, 10) + "..." : actualApiKey));
                    Log.e("ChatApiClient", "阿里云API Key应该以'sk-'开头，例如: sk-xxxxxxxxxx");
                    throw new IllegalArgumentException("阿里云API Key格式错误，应以'sk-'开头。请在设置中填入正确的阿里云API Key！");
                }
                
                // 验证阿里云URL格式
                if (!actualUrl.contains("aliyuncs.com")) {
                    Log.e("ChatApiClient", "阿里云URL格式错误！当前URL: " + actualUrl);
                    Log.e("ChatApiClient", "正确的URL应该是: https://dashscope.aliyuncs.com/compatible-mode/v1/");
                    throw new IllegalArgumentException("阿里云URL格式错误，正确格式应为: https://dashscope.aliyuncs.com/compatible-mode/v1/");
                }
                
                // 检查常见的拼写错误
                if (actualUrl.contains("aiyuncs.com")) {
                    Log.e("ChatApiClient", "检测到URL拼写错误：'aiyuncs.com' 应该是 'aliyuncs.com'");
                    throw new IllegalArgumentException("URL拼写错误：应该是 'aliyuncs.com' 而不是 'aiyuncs.com'");
                }
                
                Log.d("ChatApiClient", "阿里云配置验证通过 - URL: " + actualUrl + ", API Key格式正确");
            }
            
            Log.d("ChatApiClient", "Initializing OpenAI client with URL: " + actualUrl);
            
            chatGPT = new OpenAiStreamClient.Builder()
                    .apiKey(Arrays.asList(actualApiKey))
                    .apiHost(actualUrl)
                    .okHttpClient(httpClient)
                    .build();
                    
            Log.d("ChatApiClient", "OpenAI client initialized successfully");
            
        } catch (Exception e) {
            Log.e("ChatApiClient", "Failed to initialize OpenAI client", e);
            String err = context.getString(R.string.text_gpt_conf_error);
            if(e.getMessage() != null) {
                err += ": " + e.getMessage();
            }
            listener.onError(err);
        }
    }

    // 获取当前是否正在请求GPT
    public boolean isStreaming() {
        return httpClient.connectionPool().connectionCount() - httpClient.connectionPool().idleConnectionCount() > 0;
    }

    // 中断当前请求
    public void stop() {
        httpClient.dispatcher().cancelAll();
    }

    // 设置使用的模型
    public void setModel(String model) {
        this.model = model;
    }

    // 设置温度
    public void setTemperature(float temperature) { this.temperature = temperature; }
    
    // 测试阿里云连接
    public void testAliyunConnection() {
        if (!GlobalDataHolder.getUseAliyunChat()) {
            Log.d("ChatApiClient", "Not in Aliyun mode, skipping connection test");
            return;
        }
        
        Log.d("ChatApiClient", "=== TESTING ALIYUN CONNECTION ===");
        Log.d("ChatApiClient", "URL: " + url);
        Log.d("ChatApiClient", "API Key: " + (apiKey != null && apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey));
        Log.d("ChatApiClient", "Model: " + model);
        
        // 创建一个简单的测试消息
        ArrayList<Message> testMessages = new ArrayList<>();
        testMessages.add(Message.builder().role(Message.Role.USER).content("测试连接").build());
        
        ChatCompletion testCompletion = ChatCompletion.builder()
                .messages(testMessages)
                .model(model.replaceAll("\\*$",""))
                .temperature(0.1f)
                .maxTokens(10)
                .build();
        
        Log.d("ChatApiClient", "Sending test request...");
        
        try {
            chatGPT.streamChatCompletion(testCompletion, new EventSourceListener() {
                @Override
                public void onOpen(EventSource eventSource, Response response) {
                    Log.d("ChatApiClient", "✅ Test connection successful! Response: " + response.code());
                }
                
                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    Log.d("ChatApiClient", "✅ Test response received: " + data);
                    eventSource.cancel(); // 取消测试请求
                }
                
                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    if (response != null) {
                        Log.e("ChatApiClient", "❌ Test connection failed: HTTP " + response.code());
                        try {
                            if (response.body() != null) {
                                String errorBody = response.body().string();
                                Log.e("ChatApiClient", "❌ Test error response: " + errorBody);
                            }
                        } catch (Exception e) {
                            Log.e("ChatApiClient", "❌ Failed to read test error response", e);
                        }
                    } else {
                        Log.e("ChatApiClient", "❌ Test connection failed: " + (t != null ? t.getMessage() : "Unknown error"));
                    }
                }
                
                @Override
                public void onClosed(EventSource eventSource) {
                    Log.d("ChatApiClient", "Test connection closed");
                }
            });
        } catch (Exception e) {
            Log.e("ChatApiClient", "❌ Failed to start test connection", e);
        }
        
        Log.d("ChatApiClient", "================================");
    }

    // 添加一个函数，有同名函数则覆盖
    public void addFunction(String name, String desc, String params, String[] required) {
        removeFunction(name); // 删除同名函数

        Parameters parameters = Parameters.builder()
                .type("object")
                .properties(new JSONObject(params))
                .required(Arrays.asList(required))
                .build();

        Tools tools = Tools.builder()
                .type(Tools.Type.FUNCTION.getName())
                .function(ToolsFunction.builder()
                        .name(name)
                        .description(desc)
                        .parameters(parameters)
                        .build())
                .build();

//        Functions functions = Functions.builder()
//                .name(name)
//                .description(desc)
//                .parameters(parameters)
//                .build();

        this.functions.add(tools);
    }

    // 删除一个函数
    public void removeFunction(String name) {
        for(int i = 0; i < this.functions.size(); i++) {
            if(this.functions.get(i).getFunction().getName().equals(name)) {
                this.functions.remove(i);
                break;
            }
        }
    }

    // 删除所有函数
    public void clearAllFunctions() {
        this.functions.clear();
    }
    
    // 解析阿里云API错误响应
    private String parseAliyunError(String errorBody, int httpCode) {
        // 特殊处理404错误
        if (httpCode == 404) {
            Log.e("ChatApiClient", "HTTP 404错误 - 端点不存在");
            Log.e("ChatApiClient", "当前使用的URL: " + url);
            Log.e("ChatApiClient", "当前使用的模型: " + model);
            Log.e("ChatApiClient", "当前API Key: " + (apiKey != null && apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey));
            
            String errorMsg = "HTTP 404错误 - 请求的端点不存在\n\n";
            errorMsg += "可能的原因：\n";
            errorMsg += "1. URL格式错误 - 当前URL: " + url + "\n";
            errorMsg += "2. 模型名称错误 - 当前模型: " + model + "\n";
            errorMsg += "3. API Key无效或格式错误\n\n";
            errorMsg += "解决方案：\n";
            errorMsg += "1. 确保URL为: https://dashscope.aliyuncs.com/compatible-mode/v1/\n";
            errorMsg += "2. 确认模型名称正确（如：qwen-turbo, qwen-plus, qwen-max）\n";
            errorMsg += "3. 确认API Key以'sk-'开头且有效\n";
            errorMsg += "4. 检查网络连接和防火墙设置";
            
            return errorMsg;
        }
        
        // 处理其他错误
        try {
            if (errorBody == null || errorBody.trim().isEmpty()) {
                return "HTTP " + httpCode + " 错误，无详细信息";
            }

            // 尝试解析不同格式的错误响应
            try {
                // OpenAI兼容格式
                JSONObject errorJson = new JSONObject(errorBody);
                if (errorJson.containsKey("error")) {
                    JSONObject error = errorJson.getJSONObject("error");
                    String message = error.getStr("message", "未知错误");
                    String type = error.getStr("type", "");
                    String code = error.getStr("code", "");
                    
                    String result = "阿里云API错误: " + message;
                    if (!type.isEmpty()) result += "\n类型: " + type;
                    if (!code.isEmpty()) result += "\n错误代码: " + code;
                    
                    // 添加常见错误的解决建议
                    if (message.contains("Invalid API key") || message.contains("api key")) {
                        result += "\n\n解决方案: 请检查API Key是否正确，确保以'sk-'开头";
                    } else if (message.contains("model") && message.contains("not found")) {
                        result += "\n\n解决方案: 请检查模型名称是否正确，推荐使用: qwen-turbo, qwen-plus, qwen-max";
                    } else if (message.contains("quota") || message.contains("balance")) {
                        result += "\n\n解决方案: API配额不足，请检查账户余额或联系阿里云客服";
                    }
                    
                    return result;
                }
            } catch (Exception e) {
                // 如果不是标准JSON格式，尝试其他格式
            }

            // 尝试DashScope原生格式
            try {
                JSONObject errorJson = new JSONObject(errorBody);
                if (errorJson.containsKey("message")) {
                    String message = errorJson.getStr("message");
                    String requestId = errorJson.getStr("request_id", "");
                    
                    String result = "阿里云DashScope错误: " + message;
                    if (!requestId.isEmpty()) result += "\n请求ID: " + requestId;
                    return result;
                }
            } catch (Exception e) {
                // 如果也不是DashScope格式，使用原始响应
            }

            // 如果都不是标准格式，返回原始错误信息
            return "HTTP " + httpCode + " 错误\n原始响应: " + errorBody;
            
        } catch (Exception e) {
            Log.e("ChatApiClient", "解析错误响应失败", e);
            return "HTTP " + httpCode + " 错误，解析响应失败: " + e.getMessage();
        }
    }
}
