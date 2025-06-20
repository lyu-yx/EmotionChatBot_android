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
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)  // 增加连接超时时间
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)    // 增加读取超时时间，适应流式响应
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)    // 增加写入超时时间
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
        
        Log.d("ChatApiClient", "Sending request to: " + url);
        Log.d("ChatApiClient", "Using model: " + model);
        Log.d("ChatApiClient", "API Key length: " + (apiKey != null ? apiKey.length() : 0));
        Log.d("ChatApiClient", "Is Aliyun mode: " + GlobalDataHolder.getUseAliyunChat());

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
                Log.d("ChatApiClient", "onOpen");
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
                                Log.e("ChatApiClient", "Error Response Body: " + errorBody);
                                
                                // 尝试解析阿里云API错误格式
                                String err = parseAliyunError(errorBody, response.code());
                                if(err.length() > 500) {
                                    err = err.substring(0, 500) + "...";
                                }
                                listener.onError(err);
                            } catch (IOException e) {
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
        // 根据是否使用阿里云来决定API端点
        String actualUrl = url;
        String actualApiKey = apiKey;
        
        if (GlobalDataHolder.getUseAliyunChat()) {
            // 根据阿里云官方文档，使用OpenAI兼容模式的base_url
            actualUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            // 阿里云聊天模型使用OpenAI API Key字段存储的阿里云API Key
            actualApiKey = apiKey;
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
                    Log.w("ChatApiClient", "阿里云API Key格式可能不正确，应以'sk-'开头");
                }
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
        try {
            JSONObject errorJson = new JSONObject(errorBody);
            
            // 阿里云API错误格式通常包含error字段
            if (errorJson.containsKey("error")) {
                JSONObject error = errorJson.getJSONObject("error");
                String message = error.getStr("message", "");
                String code = error.getStr("code", "");
                String type = error.getStr("type", "");
                
                StringBuilder errorMsg = new StringBuilder();
                if (!code.isEmpty()) {
                    errorMsg.append("错误代码: ").append(code).append("\n");
                }
                if (!type.isEmpty()) {
                    errorMsg.append("错误类型: ").append(type).append("\n");
                }
                if (!message.isEmpty()) {
                    errorMsg.append("错误信息: ").append(message);
                } else {
                    errorMsg.append("未知错误");
                }
                
                // 针对常见错误提供解决建议
                if (code.equals("InvalidApiKey") || message.contains("API key")) {
                    errorMsg.append("\n\n建议: 请检查阿里云API Key是否正确");
                } else if (code.equals("InsufficientBalance") || message.contains("balance")) {
                    errorMsg.append("\n\n建议: 账户余额不足，请充值");
                } else if (code.equals("RateLimitExceeded") || message.contains("rate limit")) {
                    errorMsg.append("\n\n建议: 请求频率过高，请稍后重试");
                } else if (code.equals("ModelNotFound") || message.contains("model")) {
                    errorMsg.append("\n\n建议: 请检查模型名称是否正确");
                }
                
                return errorMsg.toString();
            }
            
            // 如果没有标准的error字段，尝试其他可能的字段
            if (errorJson.containsKey("message")) {
                return "错误: " + errorJson.getStr("message");
            }
            
            if (errorJson.containsKey("detail")) {
                return "错误: " + errorJson.getStr("detail");
            }
            
        } catch (Exception e) {
            Log.w("ChatApiClient", "Failed to parse error response as JSON: " + e.getMessage());
        }
        
        // 如果无法解析JSON，返回原始错误信息和HTTP状态码
        String fallbackMsg = "HTTP " + httpCode + " 错误";
        if (httpCode == 401) {
            fallbackMsg += "\n可能原因: API Key无效或已过期";
        } else if (httpCode == 403) {
            fallbackMsg += "\n可能原因: 没有访问权限或余额不足";
        } else if (httpCode == 429) {
            fallbackMsg += "\n可能原因: 请求频率过高，请稍后重试";
        } else if (httpCode == 500) {
            fallbackMsg += "\n可能原因: 服务器内部错误，请稍后重试";
        }
        
        return fallbackMsg + "\n\n原始响应: " + errorBody;
    }
}
