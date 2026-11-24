package org.vaadin.marcus.v1;

import dev.langchain4j.model.openai.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.data.message.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Service
public class ModelService {
    final OpenAiChatModel chat;
    final OpenAiChatModel rerank;
    final OpenAiStreamingChatModel stream;
    final OpenAiEmbeddingModel embedding;
    final String modelName,rerankName;
    final TraceService traces;
    final RunBudget budget;
    ModelService(TraceService traces,RunBudget budget,@Value("${app.ai.base-url}") String base,@Value("${app.ai.key}") String key,
                 @Value("${app.ai.model}") String model,@Value("${app.ai.rerank-model}") String rank,
                 @Value("${app.embedding.base-url}") String embBase,@Value("${app.embedding.model}") String embModel) {
        this.traces=traces;this.budget=budget;this.modelName=model;this.rerankName=rank;
        var custom=Map.<String,Object>of("thinking",Map.of("type","disabled"));
        chat=OpenAiChatModel.builder().baseUrl(base).apiKey(key).modelName(model).temperature(0.0).maxTokens(1800).maxRetries(0).timeout(Duration.ofSeconds(90)).customParameters(custom).logRequests(false).logResponses(false).build();
        rerank=OpenAiChatModel.builder().baseUrl(base).apiKey(key).modelName(rank).temperature(0.0).maxTokens(1200).maxRetries(0).timeout(Duration.ofSeconds(90)).customParameters(custom).logRequests(false).logResponses(false).build();
        stream=OpenAiStreamingChatModel.builder().baseUrl(base).apiKey(key).modelName(model).temperature(0.0).maxTokens(1800).timeout(Duration.ofSeconds(90)).customParameters(custom).logRequests(false).logResponses(false).build();
        embedding=OpenAiEmbeddingModel.builder().baseUrl(embBase).apiKey("local-cpu").modelName(embModel).timeout(Duration.ofSeconds(90)).maxRetries(0).build();
    }
    ChatResponse call(UUID id,String purpose,ChatRequest request,boolean ranking) {
        budget.use(id,"models");
        long t=System.nanoTime();
        try {
            var response=(ranking?rerank:chat).chat(request);
            traces.usage(id,purpose,ranking?rerankName:modelName,response,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t));
            budget.check(id);
            return response;
        } catch(RunBudget.Limit e){throw e;}catch(Exception e) {traces.event(id,"MODEL_ERROR",Map.of("purpose",purpose,"reason","模型连接、限流或响应格式异常","type",e.getClass().getSimpleName()));throw new IllegalStateException("模型暂时不可用，请通过请求记录排查后重试");}
    }
    String text(UUID id,String purpose,String system,String user,boolean ranking) {
        return call(id,purpose,ChatRequest.builder().messages(SystemMessage.from(system),UserMessage.from(user)).build(),ranking).aiMessage().text();
    }
    String answer(UUID id,List<ChatMessage> messages,long started,Consumer<String> delta) {
        budget.use(id,"models");
        long t=System.nanoTime();var done=new CompletableFuture<ChatResponse>();var output=new StringBuilder();
        stream.chat(ChatRequest.builder().messages(messages).build(),new StreamingChatResponseHandler(){
            @Override public void onPartialResponse(String token) {if(!token.isEmpty()){traces.first(id,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));output.append(token);delta.accept(token);}}
            @Override public void onCompleteResponse(ChatResponse response) {done.complete(response);}
            @Override public void onError(Throwable error) {done.completeExceptionally(error);}
        });
        try {var response=done.get(120,TimeUnit.SECONDS);traces.usage(id,"answer-stream",modelName,response,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t));budget.check(id);return output.toString();}
        catch(Exception e){throw new IllegalStateException("模型流式响应失败，请重试；订单尚未因聊天而修改");}
    }
    float[] embed(String text,boolean query) {
        return embedding.embed(query?"为这个句子生成表示以用于检索相关文章："+text:text).content().vector();
    }
}
