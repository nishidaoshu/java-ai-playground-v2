package org.vaadin.marcus.v1;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.demo.enabled",havingValue="true",matchIfMissing=true)
public class DemoData implements CommandLineRunner {
    final Users users;final Orders orders;final PasswordEncoder encoder;final Documents docs;final RagService rag;
    DemoData(Users users,Orders orders,PasswordEncoder encoder,Documents docs,RagService rag){this.users=users;this.orders=orders;this.encoder=encoder;this.docs=docs;this.rag=rag;}
    @Override public void run(String...args)throws Exception{
        for(String name:List.of("alice","bob")){
            if(users.findByUsername(name).isPresent())continue;
            String password=System.getenv("DEMO_"+name.toUpperCase()+"_PASSWORD");
            if(password==null||password.length()<12)throw new IllegalStateException("首次启动需要通过本机环境配置两个至少12位的演示账号密码");
            var u=new Domain.User();u.username=name;u.passwordHash=encoder.encode(password);u.displayName=name.equals("alice")?"演示旅客 · Alice":"演示旅客 · Bob";u.role="USER";users.saveAndFlush(u);
            for(int i=1;i<=4;i++){
                var o=new Domain.Order();o.id=(name.equals("alice")?"A":"B")+"100"+i;o.userId=u.id;o.flightDate=LocalDate.now(TransactionalWorkflow.ZONE).plusDays(14+i);o.origin="南京 NKG";o.destination=i%2==0?"上海 SHA":"北京 PEK";o.cabin=i==4?"BUSINESS":"ECONOMY";o.status="CONFIRMED";o.fare=new BigDecimal(i==4?"1500.00":"1000.00");orders.saveAndFlush(o);
            }
        }
        for(String file:List.of("refund.md","change.md","service.md")){
            String title=switch(file){case "refund.md"->"演示退票服务条款";case "change.md"->"演示改签与舱位条款";default->"演示行李与客服说明";};
            if(docs.findByTitleAndVersionAndOwnerIdIsNull(title,TransactionalWorkflow.RULE_VERSION).isEmpty()){
                String text=new ClassPathResource("demo/"+file).getContentAsString(StandardCharsets.UTF_8);
                rag.ingest(null,file,title,TransactionalWorkflow.RULE_VERSION,text,true);
            }
        }
    }
}
