package org.vaadin.marcus.v1;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

@RestController @RequestMapping("/api")
@org.springframework.context.annotation.Profile("!mcp")
public class Api {
    final Identity identity;final WorkflowService workflow;final RagService rag;final ChatService chat;final Plans plans;final Operations operations;final TraceService traces;
    final ExecutorService executor=Executors.newFixedThreadPool(4);
    final McpGateway mcp;final SkillRegistry skills;final RunBudget budget;
    Api(Identity identity,WorkflowService workflow,RagService rag,ChatService chat,Plans plans,Operations operations,TraceService traces,McpGateway mcp,SkillRegistry skills,RunBudget budget){this.identity=identity;this.workflow=workflow;this.rag=rag;this.chat=chat;this.plans=plans;this.operations=operations;this.traces=traces;this.mcp=mcp;this.skills=skills;this.budget=budget;}
    static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在或无权访问");}
    static ResponseStatusException bad(String reason){return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
    static ResponseStatusException conflict(String reason){return new ResponseStatusException(HttpStatus.CONFLICT,reason);}
    UUID request(HttpServletRequest r){return (UUID)r.getAttribute("requestId");}
    @GetMapping("/health") Map<String,Object> health(){return Map.of("application","java-ai-playground-v2","status","UP","demonstration",true);}
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token){return Map.of("headerName",token.getHeaderName(),"parameterName",token.getParameterName(),"token",token.getToken());}
    @GetMapping("/me") Domain.User me(){return identity.current();}
    @GetMapping("/orders") List<Domain.Order> orders(){return workflow.list(identity.current().id);}
    @GetMapping("/orders/{id}") Domain.Order order(@PathVariable String id){return workflow.get(identity.current().id,id);}
    @GetMapping("/plans") List<Domain.Plan> plans(){return plans.findByUserIdOrderByCreatedAtDesc(identity.current().id);}
    public record Prepare(String orderId,String action,LocalDate newDate,String newCabin){}
    @PostMapping("/plans") Domain.Plan prepare(@RequestBody Prepare input,HttpServletRequest req){
        var owner=identity.current().id;workflow.get(owner,input.orderId());UUID trace=request(req);budget.open(trace,owner);
        var skill=skills.get("change_plan");UUID span=budget.startAgent(trace,null,"order-form",skill);String error=null;
        try(var connection=mcp.connect(owner,trace,span,skill)){
            if(!Set.of("CANCEL","CHANGE").contains(Objects.toString(input.action(),"")))throw bad("只支持CANCEL或CHANGE");
            Map<String,Object> args=new LinkedHashMap<>();args.put("orderId",input.orderId());
            if("CHANGE".equals(input.action())){args.put("newDate",Objects.toString(input.newDate(),""));args.put("newCabin",Objects.toString(input.newCabin(),""));}
            var data=connection.call("CANCEL".equals(input.action())?"propose_cancellation":"propose_change",args);
            if(data.has("error"))throw conflict(data.path("error").asText());
            return traces.json.treeToValue(data,Domain.Plan.class);
        }catch(Exception e){error=ChatService.safeFailure(e);if(e instanceof ResponseStatusException r)throw r;throw new IllegalStateException(error);}
        finally{budget.end(trace,span,error);traces.event(trace,"BUDGET_END",budget.snapshot(trace));}
    }
    // No model-facing tool is capable of calling this authenticated, CSRF-protected endpoint.
    @PostMapping("/plans/{id}/confirm") Domain.Plan confirm(@PathVariable UUID id,HttpServletRequest req){
        var result=workflow.confirm(identity.current().id,id,request(req));
        traces.event(request(req),"CONFIRM_RESULT",Map.of("planId",result.id,"orderId",result.orderId,"status",result.status,"resultVersion",result.resultVersion,"fee",result.fee,"refund",result.refund));return result;
    }
    @PostMapping("/plans/{id}/discard") Domain.Plan discard(@PathVariable UUID id,HttpServletRequest req){
        var result=workflow.discard(identity.current().id,id,request(req));
        traces.event(request(req),"DISCARD_RESULT",Map.of("planId",result.id,"orderId",result.orderId,"status",result.status));return result;
    }
    @GetMapping("/operations") List<Domain.Operation> operations(){return operations.findTop100ByUserIdOrderByCreatedAtDesc(identity.current().id);}
    @GetMapping("/documents") List<Domain.Document> documents(){return rag.documents(identity.current().id);}
    @PostMapping(value="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Domain.Document upload(@RequestParam MultipartFile file,@RequestParam String title,@RequestParam String version,HttpServletRequest req) throws Exception {
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();}catch(CharacterCodingException e){throw bad("文档必须使用 UTF-8 编码");}
        var d=rag.ingest(identity.current().id,file.getOriginalFilename(),title,version,text,false);
        traces.event(request(req),"INGEST",Map.of("documentId",d.id,"title",d.title,"version",d.version,"embeddingModel","bge-small-zh-v1.5 (CPU ONNX int8)"));return d;
    }
    @DeleteMapping("/documents/{id}") void delete(@PathVariable UUID id){rag.delete(identity.current().id,id);}
    @GetMapping("/conversations") List<Map<String,Object>> conversations(){return budget.db.queryForList("select c.id,c.created_at as \"createdAt\",coalesce((select left(m.content,45) from chat_messages m where m.conversation_id=c.id and m.role='user' order by m.id limit 1),'新对话') as title from conversations c where c.user_id=? order by c.created_at desc",identity.current().id);}
    @PostMapping("/conversations") Domain.Conversation create(){return chat.create(identity.current().id);}
    @GetMapping("/conversations/{id}/messages") List<Domain.Message> history(@PathVariable UUID id){return chat.history(identity.current().id,id);}
    public record Question(UUID conversationId,String message){}
    @PostMapping(value="/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chat(@RequestBody Question q,HttpServletRequest req){
        var owner=identity.current().id;
        if(q.message()==null||q.message().isBlank()||q.message().length()>4000)throw bad("消息长度应为1到4000字");
        chat.owned(owner,q.conversationId());UUID trace=request(req);
        var emitter=new SseEmitter(360000L);req.setAttribute("chatOwnsTrace",true);
        executor.submit(()->{
            chat.run(owner,q.conversationId(),q.message(),trace,(event,data)->{try{emitter.send(SseEmitter.event().name(event).data(data));}catch(Exception ignored){/* Persist result even after browser disconnect. */}});
            emitter.complete();
        });return emitter;
    }
    @GetMapping("/traces") Map<String,Object> traces(@RequestParam(defaultValue="")String requestId,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String type,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="15")int size){return traces.page(identity.current().id,requestId,status,type,page,size);}
    @GetMapping("/traces/{id}") List<Map<String,Object>> trace(@PathVariable UUID id){return traces.detail(identity.current().id,id);}
    @GetMapping("/runs/{id}") Map<String,Object> run(@PathVariable UUID id){traces.detail(identity.current().id,id);return Map.of("agents",budget.db.queryForList("select * from agent_runs where request_id=? order by started_at",id),"budget",budget.db.queryForList("select * from request_budgets where request_id=?",id));}
    @GetMapping("/skills") Collection<SkillRegistry.Skill> skills(){return skills.list();}
    @GetMapping("/mcp/status") Map<String,Object> mcp(HttpServletRequest req){
        Long owner=identity.current().id;UUID trace=request(req);budget.open(trace,owner);var skill=skills.get("terms_qa");UUID span=budget.startAgent(trace,null,"mcp-probe",skill);String error=null;
        try(var ignored=mcp.connect(owner,trace,span,skill)){}catch(Exception e){error=ChatService.safeFailure(e);}finally{budget.end(trace,span,error);if(error!=null)req.setAttribute("traceError",error);}
        return mcp.recent;
    }
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> error(Exception ex,HttpServletRequest req){
        int code=500;String message="处理失败，请查看请求记录或稍后重试";
        if(ex instanceof ResponseStatusException e){code=e.getStatusCode().value();message=e.getReason();}
        else if(ex instanceof org.springframework.dao.DataIntegrityViolationException){code=409;message="文档标题和版本已存在，或数据约束冲突";}
        else if(ex instanceof IllegalArgumentException){code=400;message="参数格式不合法";}
        else if(ex instanceof IllegalStateException||ex instanceof RunBudget.Limit){code=503;message=ChatService.safeFailure(ex);}
        req.setAttribute("traceError",message);
        return ResponseEntity.status(code).body(Map.of("message",message==null?"请求失败":message,"requestId",Objects.toString(req.getAttribute("requestId"),"")));
    }
    @jakarta.annotation.PreDestroy void close(){executor.shutdownNow();}
}
