package org.vaadin.marcus.v1;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.io.IOException;
import java.util.*;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        var id=UUID.randomUUID();req.setAttribute("requestId",id);res.setHeader("X-Request-ID",id.toString());chain.doFilter(req,res);
    }
}
@Component
@org.springframework.context.annotation.Profile("!mcp")
public class RequestTracking implements WebMvcConfigurer {
    final TraceService traces;final Identity identity;
    RequestTracking(TraceService traces,Identity identity){this.traces=traces;this.identity=identity;}
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
                if(req.getDispatcherType()!=DispatcherType.REQUEST)return true;
                var path=req.getRequestURI();
                if(path.equals("/api/csrf")||path.equals("/api/health"))return true;
                var id=(UUID)req.getAttribute("requestId");
                traces.start(id,identity.current().id,path);
                req.setAttribute("traceStarted",System.nanoTime());return true;
            }
            @Override public void afterCompletion(HttpServletRequest req,HttpServletResponse res,Object handler,Exception ex){
                var start=(Long)req.getAttribute("traceStarted");
                if(start!=null&&!Boolean.TRUE.equals(req.getAttribute("chatOwnsTrace")))traces.finish((UUID)req.getAttribute("requestId"),(System.nanoTime()-start)/1000000,(String)req.getAttribute("traceError"));
            }
        }).addPathPatterns("/api/**");
    }
}
