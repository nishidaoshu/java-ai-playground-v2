package org.vaadin.marcus.v1;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL + pgvector, real transactions and locks; model calls deliberately mocked. */
@SpringBootTest(properties={"app.demo.enabled=false","vaadin.productionMode=true"})
@AutoConfigureMockMvc
class WorkflowIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("DB_URL").replace("/java_ai_v2","/java_ai_v2_test"));}
    @Autowired JdbcTemplate db;@Autowired Users users;@Autowired Orders orders;@Autowired Plans plans;@Autowired Operations ops;
    @Autowired WorkflowService workflow;@Autowired TransactionalWorkflow tx;@Autowired TraceService traces;@Autowired MockMvc mvc;
    @Autowired ChatService chat;@Autowired RagService rag;@Autowired RunBudget budget;
    @MockBean ModelService models;
    Long alice,bob;UUID trace;
    final List<RagService.Evidence> evidence=List.of(new RagService.Evidence(UUID.randomUUID(),UUID.randomUUID(),"测试权威条款","demo-v1.0","用于隔离业务测试的模拟检索证据",1,1,true));
    @BeforeEach void seed(){
        assertEquals("java_ai_v2_test",db.queryForObject("select current_database()",String.class));
        db.execute("truncate app_users cascade");
        for(String name:List.of("alice","bob")){var u=new Domain.User();u.username=name;u.passwordHash="$2a$12$testOnly";u.displayName=name;u.role="USER";users.saveAndFlush(u);if(name.equals("alice"))alice=u.id;else bob=u.id;}
        for(String id:List.of("A1","A2","B1")){var o=new Domain.Order();o.id=id;o.userId=id.startsWith("A")?alice:bob;o.flightDate=LocalDate.now(TransactionalWorkflow.ZONE).plusDays(20);o.origin="NKG";o.destination="PEK";o.cabin="ECONOMY";o.status="CONFIRMED";o.fare=new BigDecimal("1000.00");orders.saveAndFlush(o);}
        trace=traces.start(alice,"test");
    }
    Domain.Plan cancellation(){return workflow.prepare(alice,"A1","CANCEL",null,null,evidence,trace);}
    long successful(){return db.queryForObject("select count(*) from operation_records where action='CONFIRM' and outcome='SUCCESS'",Long.class);}
    @Test void ownershipOnRead(){assertThrows(ResponseStatusException.class,()->workflow.get(alice,"B1"));assertEquals(2,workflow.list(alice).size());}
    @Test void ownershipOnPreparation(){assertThrows(ResponseStatusException.class,()->workflow.prepare(alice,"B1","CANCEL",null,null,evidence,trace));assertEquals("CONFIRMED",orders.findById("B1").orElseThrow().status);assertEquals(1,ops.findAll().size());}
    @Test void planDoesNotMutateDatabase(){var p=cancellation();assertEquals("PENDING",p.status);assertEquals(new BigDecimal("200.00"),p.fee);assertEquals(new BigDecimal("800.00"),p.refund);assertEquals("CONFIRMED",db.queryForObject("select status from flight_orders where id='A1'",String.class));assertEquals(0,successful());}
    @Test void confirmCommitsAndAudits(){var p=cancellation();var result=workflow.confirm(alice,p.id,trace);assertEquals("EXECUTED",result.status);assertEquals("CANCELLED",db.queryForObject("select status from flight_orders where id='A1'",String.class));assertEquals(1L,db.queryForObject("select version from flight_orders where id='A1'",Long.class));assertEquals(1,successful());}
    @Test void anotherUserCannotConfirm(){var p=cancellation();assertThrows(ResponseStatusException.class,()->workflow.confirm(bob,p.id,trace));assertEquals("CONFIRMED",orders.findById("A1").orElseThrow().status);}
    @Test void repeatedConfirmationIsIdempotent(){var p=cancellation();workflow.confirm(alice,p.id,trace);workflow.confirm(alice,p.id,trace);assertEquals(1,successful());assertEquals(1,orders.findById("A1").orElseThrow().version);assertEquals(1L,db.queryForObject("select count(*) from operation_records where outcome='REPLAY'",Long.class));}
    @Test void discardPreservesOrderAndAudits(){
        var p=cancellation();var before=db.queryForMap("select * from flight_orders where id='A1'");
        assertEquals("DISCARDED",workflow.discard(alice,p.id,trace).status);
        assertEquals("DISCARDED",db.queryForObject("select status from operation_plans where id=?",String.class,p.id));
        assertEquals(before,db.queryForMap("select * from flight_orders where id='A1'"));
        assertEquals(1L,db.queryForObject("select count(*) from operation_records where plan_id=? and action='DISCARD' and outcome='SUCCESS'",Long.class,p.id));
    }
    @Test void expiredAndStalePlansCanBeDiscarded(){
        var expired=cancellation();var stale=cancellation();
        db.update("update operation_plans set expires_at=now()-interval '1 second' where id=?",expired.id);
        db.update("update flight_orders set version=version+1 where id='A1'");
        var before=db.queryForMap("select * from flight_orders where id='A1'");
        assertEquals("DISCARDED",workflow.discard(alice,expired.id,trace).status);
        assertEquals("DISCARDED",workflow.discard(alice,stale.id,trace).status);
        assertEquals(before,db.queryForMap("select * from flight_orders where id='A1'"));assertEquals(0,successful());
    }
    @Test void anotherUserCannotDiscard()throws Exception{
        var p=cancellation();
        mvc.perform(post("/api/plans/"+p.id+"/discard").with(user("bob").roles("USER")).with(csrf())).andExpect(status().isNotFound());
        assertEquals("PENDING",plans.findById(p.id).orElseThrow().status);assertEquals("CONFIRMED",orders.findById("A1").orElseThrow().status);
    }
    @Test void discardedPlanCannotBeConfirmed(){
        var p=cancellation();workflow.discard(alice,p.id,trace);
        var error=assertThrows(ResponseStatusException.class,()->workflow.confirm(alice,p.id,trace));
        assertTrue(error.getReason().contains("已丢弃"));assertEquals("CONFIRMED",orders.findById("A1").orElseThrow().status);assertEquals(0,successful());
    }
    @Test void repeatedDiscardIsIdempotent(){
        var p=cancellation();workflow.discard(alice,p.id,trace);workflow.discard(alice,p.id,trace);
        assertEquals(1L,db.queryForObject("select count(*) from operation_records where plan_id=? and action='DISCARD' and outcome='SUCCESS'",Long.class,p.id));
        assertEquals(1L,db.queryForObject("select count(*) from operation_records where plan_id=? and action='DISCARD' and outcome='REPLAY'",Long.class,p.id));
        assertEquals(0,orders.findById("A1").orElseThrow().version);
    }
    @Test void executedPlanCannotBeDiscarded(){
        var p=cancellation();workflow.confirm(alice,p.id,trace);assertThrows(ResponseStatusException.class,()->workflow.discard(alice,p.id,trace));
        assertEquals("EXECUTED",plans.findById(p.id).orElseThrow().status);assertEquals("CANCELLED",orders.findById("A1").orElseThrow().status);assertEquals(1,successful());
    }
    @Test void discardIsCsrfProtected()throws Exception{
        var p=cancellation();
        mvc.perform(post("/api/plans/"+p.id+"/discard").with(user("alice").roles("USER"))).andExpect(status().isForbidden());
        assertEquals("PENDING",plans.findById(p.id).orElseThrow().status);
        mvc.perform(post("/api/plans/"+p.id+"/discard").with(user("alice").roles("USER")).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISCARDED"));
    }
    @Test void confirmAndDiscardAreSerialized()throws Exception{
        var p=cancellation();var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try{
            var confirmed=pool.submit(()->{ready.countDown();start.await();try{workflow.confirm(alice,p.id,trace);return true;}catch(ResponseStatusException e){return false;}});
            var discarded=pool.submit(()->{ready.countDown();start.await();try{workflow.discard(alice,p.id,trace);return true;}catch(ResponseStatusException e){return false;}});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();boolean didConfirm=confirmed.get(15,TimeUnit.SECONDS),didDiscard=discarded.get(15,TimeUnit.SECONDS);
            assertNotEquals(didConfirm,didDiscard);
            assertEquals(didConfirm?"EXECUTED":"DISCARDED",db.queryForObject("select status from operation_plans where id=?",String.class,p.id));
            assertEquals(didConfirm?"CANCELLED":"CONFIRMED",db.queryForObject("select status from flight_orders where id='A1'",String.class));
            assertEquals(didConfirm?1L:0L,db.queryForObject("select version from flight_orders where id='A1'",Long.class));assertEquals(didConfirm?1:0,successful());
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void expiredPlanRejectedAndFailurePersisted(){var p=cancellation();db.update("update operation_plans set expires_at=now()-interval '1 second' where id=?",p.id);assertThrows(ResponseStatusException.class,()->workflow.confirm(alice,p.id,trace));assertEquals("CONFIRMED",orders.findById("A1").orElseThrow().status);assertEquals("PENDING",plans.findById(p.id).orElseThrow().status);assertEquals(1L,db.queryForObject("select count(*) from operation_records where outcome='FAILED'",Long.class));}
    @Test void staleOrderVersionRejected(){var p=cancellation();db.update("update flight_orders set version=version+1 where id='A1'");assertThrows(ResponseStatusException.class,()->workflow.confirm(alice,p.id,trace));assertEquals("CONFIRMED",orders.findById("A1").orElseThrow().status);assertEquals(0,successful());}
    @Test void concurrentSamePlanExecutesOnce()throws Exception{var p=cancellation();var pool=Executors.newFixedThreadPool(4);try{var tasks=new ArrayList<Future<Domain.Plan>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->workflow.confirm(alice,p.id,trace)));for(var f:tasks)assertEquals("EXECUTED",f.get(15,TimeUnit.SECONDS).status);assertEquals(1,successful());assertEquals(1,orders.findById("A1").orElseThrow().version);}finally{pool.shutdownNow();}}
    @Test void concurrentDifferentPlansRejectStale()throws Exception{var p1=cancellation();var p2=cancellation();var pool=Executors.newFixedThreadPool(2);try{Callable<Boolean>a=()->{try{workflow.confirm(alice,p1.id,trace);return true;}catch(ResponseStatusException e){return false;}};Callable<Boolean>b=()->{try{workflow.confirm(alice,p2.id,trace);return true;}catch(ResponseStatusException e){return false;}};var result=pool.invokeAll(List.of(a,b));assertNotEquals(result.get(0).get(),result.get(1).get());assertEquals(1,successful());assertEquals(1,orders.findById("A1").orElseThrow().version);}finally{pool.shutdownNow();}}
    @Test void validationBeforeExecutionRejectsChangedFlight(){var p=cancellation();db.update("update flight_orders set flight_date=current_date where id='A1'");assertThrows(ResponseStatusException.class,()->workflow.confirm(alice,p.id,trace));assertEquals("CONFIRMED",orders.findById("A1").orElseThrow().status);}
    @Test void transactionRollsBackIfAuditFails(){var p=cancellation();db.execute("alter table operation_records add constraint test_fail_success check (not (action='CONFIRM' and outcome='SUCCESS'))");try{assertThrows(RuntimeException.class,()->workflow.confirm(alice,p.id,trace));assertEquals("CONFIRMED",orders.findById("A1").orElseThrow().status);assertEquals("PENDING",plans.findById(p.id).orElseThrow().status);assertEquals(0,orders.findById("A1").orElseThrow().version);}finally{db.execute("alter table operation_records drop constraint test_fail_success");}}
    @Test void verificationReadsRealSqlStateRatherThanJpaCache(){
        var p=cancellation();
        db.execute("create function test_corrupt_order_update() returns trigger language plpgsql as $$ begin NEW.status := 'CONFIRMED'; return NEW; end $$");
        db.execute("create trigger test_corrupt_order before update on flight_orders for each row execute function test_corrupt_order_update()");
        try{var error=assertThrows(ResponseStatusException.class,()->workflow.confirm(alice,p.id,trace));assertTrue(error.getReason().contains("数据库执行结果校验失败"));assertEquals("CONFIRMED",db.queryForObject("select status from flight_orders where id='A1'",String.class));assertEquals(0,db.queryForObject("select version from flight_orders where id='A1'",Integer.class));assertEquals("PENDING",plans.findById(p.id).orElseThrow().status);assertEquals(0,successful());}
        finally{db.execute("drop trigger test_corrupt_order on flight_orders");db.execute("drop function test_corrupt_order_update()");}
    }
    @Test void changingDateAndCabinPersistsExactFee(){var date=LocalDate.now(TransactionalWorkflow.ZONE).plusDays(30);var p=workflow.prepare(alice,"A1","CHANGE",date,"BUSINESS",evidence,trace);assertEquals(new BigDecimal("550.00"),p.fee);workflow.confirm(alice,p.id,trace);var actual=orders.findById("A1").orElseThrow();assertEquals(date,actual.flightDate);assertEquals("BUSINESS",actual.cabin);assertEquals(new BigDecimal("1500.00"),actual.fare);assertEquals(1,successful());}
    @Test void downgradeAndBadDatesRejected(){assertThrows(ResponseStatusException.class,()->workflow.prepare(alice,"A1","CHANGE",LocalDate.now(),"ECONOMY",evidence,trace));db.update("update flight_orders set cabin='BUSINESS' where id='A1'");assertThrows(ResponseStatusException.class,()->workflow.prepare(alice,"A1","CHANGE",LocalDate.now().plusDays(5),"ECONOMY",evidence,trace));}
    @Test void missingRuleEvidenceCannotPrepare(){assertThrows(ResponseStatusException.class,()->workflow.prepare(alice,"A1","CANCEL",null,null,List.of(),trace));assertEquals(0,plans.count());}
    @Test void httpApiRejectsOtherUsersOrders()throws Exception{mvc.perform(get("/api/orders/B1").with(user("alice").roles("USER"))).andExpect(status().isNotFound());}
    @Test void csrfRequiredForConfirmation()throws Exception{var p=cancellation();mvc.perform(post("/api/plans/"+p.id+"/confirm").with(user("alice").roles("USER"))).andExpect(status().isForbidden());assertEquals("CONFIRMED",orders.findById("A1").orElseThrow().status);}
    @Test void chatHistoryIsOwned()throws Exception{var id=UUID.randomUUID();db.update("insert into conversations(id,user_id,created_at) values (?,?,now())",id,bob);mvc.perform(get("/api/conversations/"+id+"/messages").with(user("alice").roles("USER"))).andExpect(status().isNotFound());}
    @Test void traceIsOwned()throws Exception{var id=traces.start(bob,"private");mvc.perform(get("/api/traces/"+id).with(user("alice").roles("USER"))).andExpect(status().isNotFound());}
    @Test void documentsCanBeListed()throws Exception{mvc.perform(get("/api/documents").with(user("alice").roles("USER"))).andExpect(status().isOk());}
    @Test void noEvidenceRetrievalIsBoundedAndDoesNotProduceSources(){
        var vector=new float[512];vector[0]=1;
        when(models.embed(anyString(),eq(true))).thenReturn(vector);
        when(models.text(any(),eq("rewrite-retry"),anyString(),anyString(),eq(false))).thenReturn("国际签证通过要求");
        budget.open(trace,alice);
        assertTrue(rag.search(alice,"国际签证保证通过",false,trace).isEmpty());
        assertEquals(2,db.queryForObject("select count(*) from trace_events where request_id=? and kind='RETRIEVE'",Integer.class,trace));
        assertEquals(2,((Number)budget.snapshot(trace).get("retrievals")).intValue());
    }
    @Test void documentsAreOwned()throws Exception{var id=UUID.randomUUID();db.update("insert into knowledge_documents(id,owner_id,title,version,filename,content,sha256,created_at) values (?,?,'私有','1','x.txt','x','hash',now())",id,bob);mvc.perform(delete("/api/documents/"+id).with(user("alice").roles("USER")).with(csrf())).andExpect(status().isNotFound());assertEquals(1L,db.queryForObject("select count(*) from knowledge_documents where id=?",Long.class,id));}
}
