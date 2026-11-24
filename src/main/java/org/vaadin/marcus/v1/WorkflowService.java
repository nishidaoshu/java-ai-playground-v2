package org.vaadin.marcus.v1;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class WorkflowService {
    final Orders orders;final Plans plans;final TransactionalWorkflow execution;final AuditService audit;
    WorkflowService(Orders orders,Plans plans,TransactionalWorkflow execution,AuditService audit){this.orders=orders;this.plans=plans;this.execution=execution;this.audit=audit;}
    public List<Domain.Order> list(Long owner){return orders.findByUserIdOrderById(owner);}
    public Domain.Order get(Long owner,String id){return orders.findByIdAndUserId(id,owner).orElseThrow(Api::notFound);}
    public Domain.Plan prepare(Long owner,String id,String action,LocalDate date,String cabin,List<RagService.Evidence> evidence,UUID trace){
        try{return execution.prepare(owner,id,action,date,cabin,evidence,trace);}
        catch(RuntimeException e){audit.record(owner,null,id,action,"FAILED",safe(e),trace);throw e;}
    }
    public Domain.Plan confirm(Long owner,UUID id,UUID trace){
        try{return execution.confirm(owner,id,trace);}
        catch(RuntimeException e){audit.record(owner,id,null,"CONFIRM","FAILED",safe(e),trace);throw e;}
    }
    public Domain.Plan discard(Long owner,UUID id,UUID trace){
        try{return execution.discard(owner,id,trace);}
        catch(RuntimeException e){audit.record(owner,id,null,"DISCARD","FAILED",safe(e),trace);throw e;}
    }
    static String safe(Exception e){return e instanceof org.springframework.web.server.ResponseStatusException r ? r.getReason():"事务或并发冲突，操作未提交";}
}
@Service
class AuditService {
    final Operations operations;
    AuditService(Operations operations){this.operations=operations;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(Long owner,UUID plan,String order,String action,String outcome,String reason,UUID request){
        var op=new Domain.Operation();op.id=UUID.randomUUID();op.userId=owner;op.planId=plan;op.orderId=order;op.action=action;op.outcome=outcome;op.reason=reason;op.requestId=request;op.createdAt=Instant.now();operations.saveAndFlush(op);
    }
}
@Service
class TransactionalWorkflow {
    static final String RULE_VERSION="demo-v1.0";
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    final Orders orders;final Plans plans;final Operations operations;final TraceService traces;final long ttl;final org.springframework.jdbc.core.JdbcTemplate db;
    TransactionalWorkflow(Orders orders,Plans plans,Operations operations,TraceService traces,org.springframework.jdbc.core.JdbcTemplate db,@Value("${app.workflow.ttl-seconds}")long ttl){this.orders=orders;this.plans=plans;this.operations=operations;this.traces=traces;this.db=db;this.ttl=ttl;}
    record Fees(BigDecimal fee,BigDecimal refund){}
    Fees check(Domain.Order o,String action,LocalDate date,String cabin){
        if(!"CONFIRMED".equals(o.status))throw Api.conflict("订单已经取消，不能再次修改");
        Instant departure=o.flightDate.atTime(12,0).atZone(ZONE).toInstant();
        long hours=Duration.between(Instant.now(),departure).toHours();
        if("CANCEL".equals(action)){
            if(hours<48)throw Api.conflict("起飞前不足48小时，演示规则不允许退票");
            var fee=o.fare.multiply(new BigDecimal("BUSINESS".equals(o.cabin)?"0.10":"0.20")).setScale(2,RoundingMode.HALF_UP);
            return new Fees(fee,o.fare.subtract(fee));
        }
        if(!"CHANGE".equals(action))throw Api.bad("只支持退票 CANCEL 或改签 CHANGE");
        if(hours<24)throw Api.conflict("起飞前不足24小时，演示规则不允许改签");
        var today=LocalDate.now(ZONE);
        if(date==null||!date.isAfter(today)||date.isAfter(today.plusDays(365)))throw Api.bad("改签日期必须在明天至未来365天内");
        if(!Set.of("ECONOMY","BUSINESS").contains(cabin==null?"":cabin))throw Api.bad("只支持 ECONOMY 或 BUSINESS 舱位");
        if(date.equals(o.flightDate)&&cabin.equals(o.cabin))throw Api.bad("改签内容与当前订单相同");
        if("BUSINESS".equals(o.cabin)&&"ECONOMY".equals(cabin))throw Api.bad("演示规则不支持降舱");
        BigDecimal fee=new BigDecimal("BUSINESS".equals(o.cabin)?"0.00":"50.00");
        if(!cabin.equals(o.cabin))fee=fee.add(new BigDecimal("500.00"));
        return new Fees(fee,BigDecimal.ZERO.setScale(2));
    }
    @Transactional
    public Domain.Plan prepare(Long owner,String id,String action,LocalDate date,String cabin,List<RagService.Evidence> evidence,UUID trace){
        var o=orders.lockOwned(id,owner).orElseThrow(Api::notFound);
        var fees=check(o,action,date,cabin);
        if(evidence.stream().noneMatch(e->e.authoritative()&&RULE_VERSION.equals(e.version())))throw Api.conflict("没有检索到当前有效演示规则，不能生成操作方案");
        var p=new Domain.Plan();p.id=UUID.randomUUID();p.userId=owner;p.orderId=id;p.orderVersion=o.version;p.action=action;p.newDate=date;p.newCabin=cabin;p.fee=fees.fee();p.refund=fees.refund();p.status="PENDING";p.createdAt=Instant.now();p.expiresAt=p.createdAt.plusSeconds(ttl);p.ruleVersion=RULE_VERSION;p.evidence=traces.encode(evidence);
        plans.saveAndFlush(p);saveRecord(p,trace,"PREPARE","SUCCESS","方案已生成，订单尚未变化");return p;
    }
    @Transactional
    public Domain.Plan confirm(Long owner,UUID id,UUID trace){
        var p=plans.lockOwned(id,owner).orElseThrow(Api::notFound);
        if("EXECUTED".equals(p.status)){saveRecord(p,trace,"CONFIRM","REPLAY","重复确认：返回原执行结果，未再次修改订单");return p;}
        if("DISCARDED".equals(p.status))throw Api.conflict("方案已丢弃，不能确认，请重新生成方案");
        if(!"PENDING".equals(p.status))throw Api.conflict("方案状态无效");
        if(!p.expiresAt.isAfter(Instant.now()))throw Api.conflict("方案已过期，请重新生成");
        var o=orders.lockOwned(p.orderId,owner).orElseThrow(Api::notFound);
        if(o.version!=p.orderVersion)throw Api.conflict("订单版本已变化，请重新生成方案");
        if(!RULE_VERSION.equals(p.ruleVersion))throw Api.conflict("费用规则已变化，请重新生成");
        var fees=check(o,p.action,p.newDate,p.newCabin);
        if(fees.fee().compareTo(p.fee)!=0||fees.refund().compareTo(p.refund)!=0)throw Api.conflict("费用已变化，请重新生成");
        if("CANCEL".equals(p.action))o.status="CANCELLED";
        else {if(!o.cabin.equals(p.newCabin))o.fare=o.fare.add(new BigDecimal("500.00"));o.flightDate=p.newDate;o.cabin=p.newCabin;}
        orders.saveAndFlush(o);
        p.status="EXECUTED";p.executedAt=Instant.now();p.resultVersion=o.version;plans.saveAndFlush(p);
        // Both the state change and its success audit commit in the same transaction.
        saveRecord(p,trace,"CONFIRM","SUCCESS","数据库已更新；演示操作，不产生真实扣费");
        // Read the actual SQL row on this transaction's connection, bypassing JPA's first-level cache.
        Long verified=db.queryForObject("select count(*) from flight_orders where id=? and user_id=? and status=? and version=? and flight_date=? and cabin=? and fare=?",Long.class,o.id,owner,o.status,p.resultVersion,o.flightDate,o.cabin,o.fare);
        if(verified==null||verified!=1L)throw Api.conflict("数据库执行结果校验失败，操作已回滚");
        return p;
    }
    @Transactional
    public Domain.Plan discard(Long owner,UUID id,UUID trace){
        // The same plan lock serializes discard against confirmation and duplicate submissions.
        var p=plans.lockOwned(id,owner).orElseThrow(Api::notFound);
        if("DISCARDED".equals(p.status)){saveRecord(p,trace,"DISCARD","REPLAY","方案已丢弃，未重复处理");return p;}
        if("EXECUTED".equals(p.status))throw Api.conflict("已执行的方案不能丢弃，请查看操作历史");
        if(!"PENDING".equals(p.status))throw Api.conflict("方案状态无效");
        // Expiry and stale order versions do not prevent cleaning up a pending proposal.
        p.status="DISCARDED";plans.saveAndFlush(p);
        saveRecord(p,trace,"DISCARD","SUCCESS","已丢弃待确认方案，订单未修改");return p;
    }
    void saveRecord(Domain.Plan p,UUID trace,String action,String outcome,String reason){
        var r=new Domain.Operation();r.id=UUID.randomUUID();r.userId=p.userId;r.planId=p.id;r.orderId=p.orderId;r.action=action;r.outcome=outcome;r.reason=reason;r.requestId=trace;r.createdAt=Instant.now();operations.saveAndFlush(r);
    }
}
