package org.vaadin.marcus.v1;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
@org.springframework.context.annotation.Profile("!mcp")
public class SecurityConfig extends VaadinWebSecurity {
    @Override protected void configure(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers("/api/csrf","/api/health").permitAll().requestMatchers("/api/**").authenticated());
        super.configure(http);
        setLoginView(http,"/login");
        http.formLogin(f -> f.successHandler((req,res,auth) -> {res.setStatus(200);res.setContentType("application/json");res.getWriter().write("{\"ok\":true}");})
            .failureHandler((req,res,e) -> res.sendError(401,"登录失败")));
        http.logout(l -> l.logoutSuccessHandler((req,res,auth) -> res.setStatus(200)));
        http.exceptionHandling(e -> e.authenticationEntryPoint((req,res,x) -> {if(req.getRequestURI().startsWith("/api/")){res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"请先登录\"}");}else res.sendRedirect("/login");}));
    }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService userDetails(Users users) {
        return name -> users.findByUsername(name).map(u -> org.springframework.security.core.userdetails.User.withUsername(u.username).password(u.passwordHash).roles(u.role).build())
            .orElseThrow(() -> new UsernameNotFoundException("登录失败"));
    }
}
@Component
class Identity {
    private final Users users;
    Identity(Users users) {this.users=users;}
    Domain.User current() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        return users.findByUsername(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
