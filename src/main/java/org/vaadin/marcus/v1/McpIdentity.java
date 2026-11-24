package org.vaadin.marcus.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Backend-only signed capabilities. Neither prompts, arguments nor shared connection state carry identity. */
@Service
public class McpIdentity {
    public record Grant(Long owner,UUID request,UUID span,String skill,String version,String audience,String scope,long issuedAt,long expiresAt,String nonce){}
    final byte[] signingKey,serviceToken;final ObjectMapper json;final JdbcTemplate db;final SkillRegistry skills;
    McpIdentity(@Value("${app.mcp.signing-key}")String key,@Value("${app.mcp.service-token}")String token,ObjectMapper json,JdbcTemplate db,SkillRegistry skills){
        this.signingKey=key.getBytes(StandardCharsets.UTF_8);this.serviceToken=token.getBytes(StandardCharsets.UTF_8);this.json=json;this.db=db;this.skills=skills;
        if(key.length()<32||token.length()<32)throw new IllegalArgumentException("MCP凭据至少32字符");
    }
    String issue(Long owner,UUID request,UUID span,SkillRegistry.Skill skill){
        long now=Instant.now().getEpochSecond();return sign(new Grant(owner,request,span,skill.id(),skill.version(),"java-ai-v2-mcp","query:prepare",now,now+300,UUID.randomUUID().toString()));
    }
    String sign(Grant grant){try{String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(grant));return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body));}catch(Exception e){throw new IllegalStateException("MCP签名失败");}}
    private byte[] mac(String text)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(signingKey,"HmacSHA256"));return m.doFinal(text.getBytes(StandardCharsets.US_ASCII));}
    boolean service(String authorization){return authorization!=null&&authorization.startsWith("Bearer ")&&MessageDigest.isEqual(serviceToken,authorization.substring(7).getBytes(StandardCharsets.UTF_8));}
    Grant authenticate(String authorization,String context){
        try{if(!service(authorization))throw new SecurityException();return verify(context);}
        catch(SecurityException e){db.update("insert into mcp_auth_events(valid,reason) values(false,'Missing, invalid, expired or unauthorized MCP capability')");throw new SecurityException("MCP身份上下文缺失、过期或校验失败");}
    }
    Grant verify(String token){
        try{
            if(token==null||token.length()>3000)throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);if(parts.length!=2||!MessageDigest.isEqual(mac(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            Grant g=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Grant.class);long now=Instant.now().getEpochSecond();
            if(!"java-ai-v2-mcp".equals(g.audience())||!"query:prepare".equals(g.scope())||g.expiresAt()<=now||g.issuedAt()>now+5||g.expiresAt()-g.issuedAt()>300||g.owner()==null||g.owner()<1||g.nonce()==null)throw new IllegalArgumentException();
            SkillRegistry.Skill s=skills.get(g.skill());if(!s.version().equals(g.version()))throw new IllegalArgumentException();
            if(db.queryForObject("select count(*) from agent_runs a join request_budgets b on a.request_id=b.request_id where a.id=? and a.request_id=? and b.user_id=? and a.skill_id=? and a.skill_version=? and a.state='RUNNING' and b.deadline>now()",Integer.class,g.span(),g.request(),g.owner(),g.skill(),g.version())!=1)throw new IllegalArgumentException();
            return g;
        }catch(Exception e){throw new SecurityException("MCP身份上下文缺失、过期或校验失败");}
    }
}
