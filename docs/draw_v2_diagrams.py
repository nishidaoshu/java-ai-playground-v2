from pathlib import Path
from html import escape
p=Path(__file__).parent
def diagram(name,title,rows,edges):
 w=1160;h=120+len(rows)*128
 out=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><defs><marker id="arrow" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto"><path d="M0,0 L10,4 L0,8" fill="#62846d"/></marker></defs><rect width="100%" height="100%" fill="#f4f7ef"/><g font-family="Microsoft YaHei,Arial,sans-serif" fill="#234b39">',f'<text x="50" y="50" font-size="28" font-weight="bold">{escape(title)}</text>']
 boxes={}
 for r,row in enumerate(rows):
  count=len(row);bw=(w-100-(count-1)*24)/count
  for c,(id,lines) in enumerate(row):
   x=50+c*(bw+24);y=85+r*128;boxes[id]=(x,y,bw,90)
   out.append(f'<rect x="{x}" y="{y}" width="{bw}" height="90" rx="12" fill="white" stroke="#a3bea1" stroke-width="2"/>')
   for n,line in enumerate(lines):out.append(f'<text x="{x+bw/2}" y="{y+30+n*23}" text-anchor="middle" font-size="{17 if n==0 else 14}">{escape(line)}</text>')
 for a,b in edges:
  x,y,bw,bh=boxes[a];x2,y2,bw2,bh2=boxes[b]
  path=f'M{x+bw/2},{y+bh} L{x2+bw2/2},{y2-6}'
  if a=='confirm' and b=='db':path=f'M{x+bw},{y+bh/2} L1140,{y+bh/2} L1140,{y2+bh2/2} L{x2+bw2+5},{y2+bh2/2}'
  out.append(f'<path d="{path}" stroke="#62846d" stroke-width="2" fill="none" marker-end="url(#arrow)"/>')
 out.append('</g></svg>');(p/(name+'.svg')).write_text(''.join(out),encoding='utf-8')
diagram('architecture','Funnair V2 · 实际运行架构',[
 [('ui',['React 浏览器 / 8082','Session + CSRF / 真实执行链路与回执'])],
 [('co',['协调 Agent','DeepSeek路由与改写，无工具']),('confirm',['交互确认 / 丢弃','只有后端接口可执行，MCP无此工具'])],
 [('know',['知识 Agent / terms_qa','独立提示词与工具白名单']),('order',['订单 Agent / order_lookup · change_plan','独立角色与版本化 Skill'])],
 [('client',['官方 MCP 客户端 0.14.1','真实 initialize / tools/list / tools/call'])],
 [('server',['独立 MCP JVM / 18084','Streamable HTTP / 签名身份 / 仅5工具'])],
 [('rag',['共享 RagService / WorkflowService','真实检索与统一后端算费，方案不执行'])],
 [('emb',['BGE ONNX CPU / 18083','512维 / V1权重只读共享']),('ds',['DeepSeek deepseek-flash','thinking关闭 / LLM重排 / 实际usage'])],
 [('db',['V2 PostgreSQL + pgvector / 15433','独立业务库、测试库、卷；预算/父子Agent/两端MCP追踪'])]
],[('ui','co'),('ui','confirm'),('co','know'),('co','order'),('know','client'),('order','client'),('client','server'),('server','rag'),('rag','emb'),('rag','ds'),('rag','db'),('confirm','db')])
diagram('request-flow','V2 · 一次聊天请求',[
 [('auth',['1 · Session + CSRF','捕获真实登录用户，分配新的请求关联ID'])],
 [('budget',['2 · 开启数据库总预算','2委派 / 8工具 / 2底层检索 / 12模型 / 240秒'])],
 [('route',['3 · 协调 Agent','结合所属会话历史改写问题并选择 Skill'])],
 [('agent',['4 · 至多两个专业 Agent','各自独立提示词、白名单、步骤和运行记录'])],
 [('mcp',['5 · 真实 MCP HTTP 传输','初始化、发现工具、验证短期签名身份'])],
 [('tool',['6 · 原子扣预算并执行获准工具','查本人订单 / 检索真实原文 / 后端准备方案'])],
 [('answer',['7 · 无工具最终回答','引用真实来源；区分查询、生成方案和执行'])],
 [('trace',['8 · 持久化完整执行链路','实际服务商 Token、首字及总耗时；错误如实记录'])]
],[(a,b) for a,b in zip(['auth','budget','route','agent','mcp','tool','answer'],['budget','route','agent','mcp','tool','answer','trace'])])
diagram('confirmation-flow','V2 · 确认与丢弃生命周期',[
 [('pending',['PENDING 待确认方案','聊天生成不修改订单；文字确认无效'])],
 [('confirm',['用户点击确认','Session + CSRF + 归属']),('discard',['用户点击丢弃','Session + CSRF + 归属'])],
 [('lock',['锁方案 → 锁归属订单','与丢弃竞争同一方案锁']),('dlock',['锁同一方案','过期 / 旧版本仍可清理'])],
 [('check',['重查全部业务条件','有效期、版本、状态、日期、舱位、规则、费用']),('dstate',['PENDING → DISCARDED','保留审计，订单不变；已执行拒绝'])],
 [('update',['同一事务写入','订单变更 + EXECUTED + 成功审计'])],
 [('sql',['读取真实 SQL 业务字段复核','不一致则回滚；失败另外记录'])],
 [('commit',['核对成功后提交并返回回执','订单 / 结果版本 / 费用 / 操作时间'])],
 [('repeat',['重复确认幂等返回原结果','重复丢弃幂等；已丢弃不能确认'])]
],[('pending','confirm'),('pending','discard'),('confirm','lock'),('discard','dlock'),('lock','check'),('dlock','dstate'),('check','update'),('update','sql'),('sql','commit'),('commit','repeat')])
print('Generated three SVG diagrams from final implementation')
