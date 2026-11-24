package org.vaadin.marcus.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class TraceService {
    private final JdbcTemplate db;
    final ObjectMapper json;
    TraceService(JdbcTemplate db,ObjectMapper json) {this.db=db;this.json=json;}
    public UUID start(Long owner,String path) {
        var id=UUID.randomUUID();
        start(id,owner,path);
        return id;
    }
    public void start(UUID id,Long owner,String path) {
        db.update("insert into request_traces(id,user_id,path,status,started_at) values (?,?,?,'RUNNING',now())",id,owner,path);
    }
    public String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("数据序列化失败");}
    }
    public void event(UUID id,String kind,Object value) { db.update("insert into trace_events(request_id,kind,data) values (?,?,cast(? as jsonb))",id,kind,encode(value)); }
    public void usage(UUID id,String purpose,String model,dev.langchain4j.model.chat.response.ChatResponse response,long elapsed) {
        var u=response.tokenUsage();
        // Null means the provider did not report usage: never estimate it.
        Map<String,Object> d=new LinkedHashMap<>();d.put("purpose",purpose);d.put("model",model);d.put("elapsedMs",elapsed);
        d.put("providerResponseId",response.id());
        d.put("providerModel",response.metadata().modelName());
        if(u==null)d.put("tokenUsage",null);
        else {Map<String,Object> usage=new LinkedHashMap<>();usage.put("inputTokens",u.inputTokenCount());usage.put("outputTokens",u.outputTokenCount());usage.put("totalTokens",u.totalTokenCount());d.put("tokenUsage",usage);}
        event(id,"MODEL",d);
        if(u!=null)db.update("update request_traces set input_tokens=input_tokens+?,output_tokens=output_tokens+? where id=?",u.inputTokenCount()==null?0:u.inputTokenCount(),u.outputTokenCount()==null?0:u.outputTokenCount(),id);
    }
    public void first(UUID id,long ms) {db.update("update request_traces set first_token_ms=coalesce(first_token_ms,?) where id=?",ms,id);}
    public void finish(UUID id,long ms,String error) {db.update("update request_traces set status=?,total_ms=?,error=? where id=?",error==null?"SUCCESS":"FAILED",ms,error,id);}
    public List<Map<String,Object>> list(Long owner) {return db.queryForList("select * from request_traces where user_id=? order by started_at desc limit 100",owner);}
    public List<Map<String,Object>> detail(Long owner,UUID id) {
        if(db.queryForObject("select count(*) from request_traces where id=? and user_id=?",Long.class,id,owner)==0)throw Api.notFound();
        var rows=db.queryForList("select kind,data,created_at from trace_events where request_id=? order by id",id);
        for(var row:rows)try{row.put("data",json.readTree(row.get("data").toString()));}catch(Exception e){throw new IllegalStateException("追踪JSON格式无效");}return rows;
    }
    public Map<String,Object> page(Long owner,String request,String status,String type,int page,int size){
        if(page<0||size<1||size>50||request.length()>40||!Set.of("","RUNNING","SUCCESS","FAILED").contains(status)||type.length()>100)throw Api.bad("追踪筛选参数无效");
        String where=" where user_id=? and cast(id as text) like ? and (?='' or status=?) and (?='' or path=?)";
        Object[] args={owner,request+"%",status,status,type,type};
        Long count=db.queryForObject("select count(*) from request_traces"+where,Long.class,args);
        var values=new ArrayList<Object>(Arrays.asList(args));values.add(size);values.add((long)page*size);
        return Map.of("items",db.queryForList("select * from request_traces"+where+" order by started_at desc limit ? offset ?",values.toArray()),"total",count,"page",page,"size",size);
    }
}
