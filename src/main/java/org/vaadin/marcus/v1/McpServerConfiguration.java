package org.vaadin.marcus.v1;

import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.io.IOException;

/** A separate JVM, independent socket and real official SDK server. No interactive business APIs are registered. */
@Configuration @Profile("mcp")
public class McpServerConfiguration {
    @Bean org.springframework.security.core.userdetails.UserDetailsService mcpNoInteractiveLogin(){return name->{throw new org.springframework.security.core.userdetails.UsernameNotFoundException("MCP不支持交互登录");};}
    @Bean SecurityFilterChain mcpSecurity(HttpSecurity http,McpIdentity identities)throws Exception{
        http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/mcp-health","/mcp").permitAll().anyRequest().denyAll())
            .addFilterBefore(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
                    if(req.getRequestURI().equals("/mcp")){
                        try{
                            if(req.getHeader("Origin")!=null)throw new SecurityException();
                            req.setAttribute("mcpGrant",identities.authenticate(req.getHeader("Authorization"),req.getHeader("X-MCP-Context")));
                        }catch(SecurityException e){res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"error\":\"MCP身份上下文缺失、过期或校验失败\"}");return;}
                    }
                    chain.doFilter(req,res);
                }
            },UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean HttpServletStreamableServerTransportProvider mcpTransport(){return HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").contextExtractor(req->McpTransportContext.create(Map.of("grant",req.getAttribute("mcpGrant")))).build();}
    @Bean ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(HttpServletStreamableServerTransportProvider transport){var bean=new ServletRegistrationBean<>(transport,"/mcp");bean.setAsyncSupported(true);return bean;}
    @Bean(destroyMethod="close") McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,McpTools tools,TraceService traces){
        var server=McpServer.sync(transport).serverInfo("funnair-v2-business","2.0.0").capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).build();
        for(var definition:McpTools.definitions())server.addTool(new McpServerFeatures.SyncToolSpecification(definition,(exchange,args)->{
            var grant=(McpIdentity.Grant)exchange.transportContext().get("grant");long start=System.nanoTime();
            try{
                Object value=tools.execute(grant,definition.name(),args);
                traces.event(grant.request(),"MCP_SERVER_RESULT",Map.of("tool",definition.name(),"spanId",grant.span(),"elapsedMs",(System.nanoTime()-start)/1000000,"result",value,"process",ProcessHandle.current().pid()));
                return new McpSchema.CallToolResult(traces.encode(value),false);
            }catch(Exception e){
                String error=ChatService.safeFailure(e);
                traces.event(grant.request(),"MCP_SERVER_ERROR",Map.of("tool",definition.name(),"spanId",grant.span(),"elapsedMs",(System.nanoTime()-start)/1000000,"reason",error));
                return new McpSchema.CallToolResult(traces.encode(Map.of("error",error)),true);
            }
        }));
        return server;
    }
}
@RestController @Profile("mcp")
class McpHealth {
    @GetMapping("/mcp-health") Map<String,Object> health(){return Map.of("application","java-ai-v2-mcp","status","UP","sdk","0.14.1","transport","Streamable HTTP","process",ProcessHandle.current().pid());}
}
