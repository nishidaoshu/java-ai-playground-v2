package org.vaadin.marcus.v1;

import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;

@Service
public class McpGateway {
    final String base,serviceToken;final McpIdentity identities;final TraceService traces;final ObjectMapper json;final SkillRegistry skills;
    volatile Map<String,Object> recent=Map.of("status","UNKNOWN","tools",List.of(),"lastError","尚未探测");
    McpGateway(@Value("${app.mcp.base-url}")String base,@Value("${app.mcp.service-token}")String token,McpIdentity identities,TraceService traces,ObjectMapper json,SkillRegistry skills){
        URI uri=URI.create(base);if(!Set.of("127.0.0.1","localhost").contains(uri.getHost())||!"http".equals(uri.getScheme()))throw new IllegalArgumentException("MCP必须是本机固定地址");
        this.base=base;this.serviceToken=token;this.identities=identities;this.traces=traces;this.json=json;this.skills=skills;
    }
    Connection connect(Long owner,UUID request,UUID span,SkillRegistry.Skill skill){return new Connection(owner,request,span,skill);}
    final class Connection implements AutoCloseable {
        final McpSyncClient client;final UUID request,span;final SkillRegistry.Skill skill;final List<McpSchema.Tool> discovered;
        Connection(Long owner,UUID request,UUID span,SkillRegistry.Skill skill){
            this.request=request;this.span=span;this.skill=skill;long start=System.nanoTime();
            // Immutable headers belong only to this one request/Agent capability. Never mutate a global user field.
            String context=identities.issue(owner,request,span,skill);
            var transport=HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp").openConnectionOnStartup(false)
                .clientBuilder(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER))
                .httpRequestCustomizer((builder,method,uri,body,c)->builder.setHeader("Authorization","Bearer "+serviceToken).setHeader("X-MCP-Context",context)).build();
            client=McpClient.sync(transport).clientInfo(new McpSchema.Implementation("funnair-v2-backend","2.0.0")).requestTimeout(Duration.ofSeconds(100)).initializationTimeout(Duration.ofSeconds(10)).build();
            try{
                var initialized=client.initialize();discovered=List.copyOf(client.listTools().tools());
                if(!discovered.stream().map(McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet()).equals(McpTools.PARAMETERS.keySet()))throw new IllegalStateException();
                recent=Map.of("status","CONNECTED","tools",discovered,"protocol",initialized.protocolVersion(),"sdk","0.14.1","transport","Streamable HTTP","address",base+"/mcp","checkedAt",Instant.now(),"lastError","");
                traces.event(request,"MCP_DISCOVERY",Map.of("spanId",span,"server",initialized.serverInfo(),"protocol",initialized.protocolVersion(),"tools",discovered,"elapsedMs",(System.nanoTime()-start)/1000000));
            }catch(Exception e){client.close();throw unavailable(request,span,"初始化或工具发现失败");}
        }
        JsonNode call(String name,Map<String,Object> args){
            skills.allow(skill,name);long start=System.nanoTime();traces.event(request,"MCP_CALL",Map.of("tool",name,"arguments",args,"spanId",span,"skillId",skill.id(),"skillVersion",skill.version()));
            McpSchema.CallToolResult result;
            try{result=client.callTool(new McpSchema.CallToolRequest(name,args));}catch(Exception e){throw unavailable(request,span,"工具调用连接失败");}
            try{
                String text=result.content().stream().filter(c->c instanceof McpSchema.TextContent).map(c->((McpSchema.TextContent)c).text()).findFirst().orElseThrow();
                var data=json.readTree(text);traces.event(request,"MCP_RESULT",Map.of("tool",name,"spanId",span,"elapsedMs",(System.nanoTime()-start)/1000000,"isError",Boolean.TRUE.equals(result.isError()),"result",data));
                if(Boolean.TRUE.equals(result.isError())){
                    String error=data.path("error").asText("MCP工具执行失败");if(error.contains("预算")||error.contains("总时间"))throw new RunBudget.Limit(error);
                }
                return data;
            }catch(RunBudget.Limit e){throw e;}catch(Exception e){throw new IllegalStateException("MCP工具响应格式异常");}
        }
        @Override public void close(){try{client.close();}catch(Exception ignored){}}
    }
    IllegalStateException unavailable(UUID request,UUID span,String phase){
        String error="MCP服务不可用："+phase+"，请检查本机MCP服务后重试；本次没有执行订单操作";
        var update=new LinkedHashMap<>(recent);update.put("status","DISCONNECTED");update.put("lastError",error);update.put("checkedAt",Instant.now());recent=Collections.unmodifiableMap(update);
        traces.event(request,"MCP_ERROR",Map.of("spanId",span,"reason",error,"address",base+"/mcp"));return new IllegalStateException(error);
    }
}
