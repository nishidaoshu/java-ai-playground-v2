package org.vaadin.marcus.v1;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.util.*;

/** Managed, read-only classpath configuration. Uploaded documents never enter this loader. */
@Service
public class SkillRegistry {
    public record Skill(String id,String version,String description,List<String> scenarios,String agent,
        List<String> inputRequirements,String resultFormat,List<String> allowedTools,int maxSteps,int maxToolCalls,
        List<String> requiredEvidence,List<String> failureRules) {}
    private final Map<String,Skill> skills=new LinkedHashMap<>();
    private final JsonSchema schema;private final ObjectMapper json;
    SkillRegistry(ObjectMapper json)throws Exception{
        this.json=json;
        schema=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(new ClassPathResource("skills/schema.json").getInputStream());
        for(String id:List.of("terms_qa","order_lookup","change_plan")){
            var skill=validate(json.readTree(new ClassPathResource("skills/"+id+".json").getInputStream()));
            if(!id.equals(skill.id()))throw new IllegalStateException("Skill ID不匹配");
            skills.put(id,skill);
        }
    }
    Skill validate(JsonNode node){
        if(!schema.validate(node).isEmpty())throw new IllegalArgumentException("Skill Schema校验失败，禁止启用");
        try{
            Skill s=json.treeToValue(node,Skill.class);
            Set<String> ceiling=switch(s.id()){
                case "terms_qa"->Set.of("search_knowledge");
                case "order_lookup"->Set.of("get_my_orders","get_order");
                case "change_plan"->Set.of("search_knowledge","get_my_orders","get_order","propose_cancellation","propose_change");
                default->throw new IllegalArgumentException("未知Skill");
            };
            if(!ceiling.containsAll(s.allowedTools())||!(s.id().equals("terms_qa")?"knowledge":"order").equals(s.agent()))throw new IllegalArgumentException("Skill权限超过后端上限");
            return s;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Skill格式无效");}
    }
    public Skill get(String id){Skill s=skills.get(id);if(s==null)throw Api.bad("不支持的Skill");return s;}
    public Collection<Skill> list(){return Collections.unmodifiableCollection(skills.values());}
    void allow(Skill s,String tool){if(!s.allowedTools().contains(tool))throw Api.bad("Skill工具白名单拒绝: "+tool);}
}
