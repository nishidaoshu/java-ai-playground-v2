package org.vaadin.marcus.v1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Atomic persisted counters shared by the application, every Agent and the independent MCP process. */
@Service
public class RunBudget {
    final JdbcTemplate db;final TraceService traces;final int delegations,tools,retrievals,models,seconds;
    RunBudget(JdbcTemplate db,TraceService traces,@Value("${app.budget.delegations:2}")int d,@Value("${app.budget.tools:8}")int t,
        @Value("${app.budget.retrievals:2}")int r,@Value("${app.budget.models:12}")int m,@Value("${app.budget.seconds:240}")int s){
        this.db=db;this.traces=traces;delegations=d;tools=t;retrievals=r;models=m;seconds=s;
        if(d<1||d>2||t<1||t>8||r<1||r>2||m<1||m>12||s<5||s>240)throw new IllegalArgumentException("总预算配置超出安全上限");
    }
    void open(UUID request,Long owner){
        db.update("insert into request_budgets(request_id,user_id,max_delegations,max_tools,max_retrievals,max_models,deadline) values (?,?,?,?,?,?,now()+(? * interval '1 second')) on conflict do nothing",request,owner,delegations,tools,retrievals,models,seconds);
        traces.event(request,"BUDGET_START",snapshot(request));
    }
    void check(UUID request){
        if(db.queryForObject("select count(*) from request_budgets where request_id=? and deadline>now()",Integer.class,request)!=1)throw new Limit("请求总时间已耗尽或预算不存在");
    }
    void use(UUID request,String counter){
        if(!Set.of("delegations","tools","retrievals","models").contains(counter))throw new IllegalArgumentException();
        int changed=db.update("update request_budgets set "+counter+"="+counter+"+1 where request_id=? and "+counter+"<max_"+counter+" and deadline>now()",request);
        if(changed!=1){traces.event(request,"BUDGET_DENIED",Map.of("counter",counter));throw new Limit("请求总预算已耗尽: "+counter);}
    }
    Map<String,Object> snapshot(UUID request){return db.queryForMap("select delegations,tools,retrievals,models,max_delegations,max_tools,max_retrievals,max_models,deadline from request_budgets where request_id=?",request);}
    int remainingRetrievals(UUID request){return db.queryForObject("select max_retrievals-retrievals from request_budgets where request_id=?",Integer.class,request);}
    UUID startAgent(UUID request,UUID parent,String agent,SkillRegistry.Skill skill){
        if(skill!=null&&Set.of("knowledge","order").contains(agent))use(request,"delegations");else check(request);
        UUID id=UUID.randomUUID();
        db.update("insert into agent_runs(id,request_id,parent_id,agent,skill_id,skill_version,state,max_tools,max_steps) values (?,?,?,?,?,?,'RUNNING',?,?)",id,request,parent,agent,skill==null?null:skill.id(),skill==null?null:skill.version(),skill==null?0:skill.maxToolCalls(),skill==null?1:skill.maxSteps());
        Map<String,Object> event=new LinkedHashMap<>();event.put("spanId",id);event.put("parentId",parent);event.put("agent",agent);event.put("skill",skill);traces.event(request,"AGENT_START",event);return id;
    }
    void step(UUID span){if(db.update("update agent_runs set steps=steps+1 where id=? and state='RUNNING' and steps<max_steps",span)!=1)throw new Limit("Skill步骤预算已耗尽");}
    void tool(UUID span){if(db.update("update agent_runs set tools=tools+1 where id=? and state='RUNNING' and tools<max_tools",span)!=1)throw new Limit("Skill工具预算已耗尽");}
    void end(UUID request,UUID span,String error){db.update("update agent_runs set state=?,error=?,ended_at=now() where id=?",error==null?"SUCCESS":"FAILED",error,span);traces.event(request,"AGENT_END",Map.of("spanId",span,"state",error==null?"SUCCESS":"FAILED","error",error==null?"":error));}
    public static class Limit extends RuntimeException{Limit(String message){super(message);}}
}
