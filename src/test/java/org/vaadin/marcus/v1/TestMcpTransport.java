package org.vaadin.marcus.v1;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.net.ServerSocket;
import java.io.*;

/** Real loopback HTTP + production SDK transport and tool handlers. Only ModelService is mocked. */
@TestConfiguration
class TestMcpTransport {
    static final int PORT=port();
    static int port(){try(var socket=new ServerSocket(0)){return socket.getLocalPort();}catch(IOException e){throw new IllegalStateException(e);}}
    @Bean(destroyMethod="close") Harness harness(McpIdentity identity,McpTools tools,TraceService traces)throws Exception{return new Harness(identity,tools,traces);}
    static class Harness implements AutoCloseable{
        final Tomcat tomcat;final McpSyncServer server;
        Harness(McpIdentity identity,McpTools tools,TraceService traces)throws Exception{
            tomcat=new Tomcat();tomcat.setBaseDir("target/test-mcp-tomcat");tomcat.setPort(PORT);tomcat.getConnector().setProperty("address","127.0.0.1");
            var context=tomcat.addContext("",new File("target").getAbsolutePath());context.setParentClassLoader(getClass().getClassLoader());
            var configuration=new McpServerConfiguration();var transport=configuration.mcpTransport();server=configuration.mcpServer(transport,tools,traces);
            var servlet=Tomcat.addServlet(context,"mcp",transport);servlet.setAsyncSupported(true);context.addServletMappingDecoded("/mcp","mcp");
            FilterDef filter=new FilterDef();filter.setFilterName("identity");filter.setAsyncSupported("true");filter.setFilter(new Filter(){
                @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
                    var req=(HttpServletRequest)request;var res=(HttpServletResponse)response;
                    try{req.setAttribute("mcpGrant",identity.authenticate(req.getHeader("Authorization"),req.getHeader("X-MCP-Context")));}catch(SecurityException e){res.setStatus(401);return;}chain.doFilter(req,res);
                }
            });context.addFilterDef(filter);FilterMap map=new FilterMap();map.setFilterName("identity");map.addURLPattern("/mcp");context.addFilterMap(map);tomcat.start();
        }
        @Override public void close()throws Exception{server.close();tomcat.stop();tomcat.destroy();}
    }
}
