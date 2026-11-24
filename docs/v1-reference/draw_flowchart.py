from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import html,math
p=Path(r'D:\.work\jianli\java-ai-playground-v1\docs')
W,H=2200,1680
im=Image.new('RGB',(W,H),'#f6f8f2');d=ImageDraw.Draw(im)
font_path=r'C:\Windows\Fonts\msyh.ttc'
font=lambda n:ImageFont.truetype(font_path,n)
svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}"><rect width="100%" height="100%" fill="#f6f8f2"/>']
def text(x,y,s,n=26,color='#294e38',anchor='mm'):
 d.text((x,y),s,font=font(n),fill=color,anchor=anchor)
 a='middle' if anchor=='mm' else 'start'
 svg.append(f'<text x="{x}" y="{y+int(n*.35)}" font-family="Microsoft YaHei,sans-serif" text-anchor="{a}" font-size="{n}" fill="{color}">{html.escape(s)}</text>')
def box(x,y,w,h,lines,kind='normal'):
 fill,stroke=('#edf5e8','#a8c3a5') if kind=='normal' else (('#fff4da','#d7b576') if kind=='confirm' else ('#e8f1ed','#92b5a3'))
 d.rounded_rectangle((x,y,x+w,y+h),radius=17,fill=fill,outline=stroke,width=2)
 svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="17" fill="{fill}" stroke="{stroke}" stroke-width="2"/>')
 for i,line in enumerate(lines):text(x+w/2,y+h/2+(i-(len(lines)-1)/2)*35,line,25 if i else 28)
 return(x,y,w,h)
def arrow(a,b,label=None):
 x1,y1=a;x2,y2=b;d.line((x1,y1,x2,y2),fill='#90ab91',width=4)
 angle=math.atan2(y2-y1,x2-x1);pts=[(x2,y2),(x2-15*math.cos(angle-.45),y2-15*math.sin(angle-.45)),(x2-15*math.cos(angle+.45),y2-15*math.sin(angle+.45))];d.polygon(pts,fill='#90ab91')
 svg.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="#90ab91" stroke-width="4"/><polygon points="'+ ' '.join(f'{x},{y}' for x,y in pts)+'" fill="#90ab91"/>')
 if label:text((x1+x2)/2+85,(y1+y2)/2,label,20,'#7b8d70')
text(100,75,'JAVA AI PLAYGROUND / V1',24,'#7a997a','lm')
text(100,135,'有依据的回答，可控的订单操作',47,'#244e35','lm')
text(100,190,'实际实现流程 · Java 21 / Spring Boot / LangChain4j / React Hilla',23,'#7b907b','lm')
box(690,245,820,105,['登录与请求入口','Session + CSRF · 从后端解析用户 · 请求关联 ID'],'storage')
box(690,400,820,105,['DeepSeek 上下文改写与工具决策','最多3轮 / 8次工具调用 · 身份不可由模型更改'])
arrow((1100,350),(1100,400))
box(80,570,890,100,['知识路径：解析 → 分段 → 中文 Embedding','TXT / Markdown · BGE-small-zh-v1.5 · CPU ONNX int8'])
box(1230,570,890,100,['订单路径：查询 → 检索规则 → 后端算费','订单归属检查 · 只生成方案，不修改订单'])
arrow((900,505),(525,570));arrow((1300,505),(1675,570))
box(80,720,890,100,['pgvector 检索 → LLM 重排','只访问全局或本人文档 · 有界问题改写与一次补检索'])
box(1230,720,890,100,['持久化待确认方案','订单 / 修改内容 / 费用 / 版本 / 规则证据 / 300秒有效期'],'confirm')
arrow((525,670),(525,720));arrow((1675,670),(1675,720))
box(80,870,890,115,['有依据：流式回答 + 真实引用','文档标题、版本、原文、片段ID、重排分数','无依据：明确提示资料不足'])
box(1230,870,890,115,['用户点击确认卡片','文字“确认”不执行 · 后端受保护确认接口'],'confirm')
arrow((525,820),(525,870));arrow((1675,820),(1675,870))
box(1230,1035,890,120,['事务内锁定方案和订单并重新校验','归属 / 过期 / 订单版本 / 日期 / 舱位 / 费用','重复确认返回原结果 · 不合规则回滚并记录失败'],'confirm')
arrow((1675,985),(1675,1035))
box(1230,1205,890,100,['执行 → 同事务保存记录 → 直接SQL复核','更新订单版本与方案状态 · 前端刷新订单和历史'])
arrow((1675,1155),(1675,1205))
box(80,1060,890,245,['PostgreSQL 持久化','用户 / 订单 / 方案 / 聊天 / 文档 / 向量 / 操作记录','所有业务读写按登录用户隔离','重启不重置业务数据'],'storage')
arrow((525,985),(525,1060))
box(80,1380,2040,135,['贯穿全程的持久化追踪与验证','检索问题和原文 · 重排分数 · 模型与工具 · 实际服务商Token · 首字/总耗时 · 成功和失败','真实 PostgreSQL 事务与并发测试 + 固定100评测场景 + 页面真实DeepSeek验证'],'storage')
arrow((525,1305),(525,1380));arrow((1675,1305),(1675,1380))
text(100,1590,'仅演示订单，无真实扣费。未实现 MCP、Skills、多 Agent 或外部追踪平台。',23,'#899782','lm')
svg.append('</svg>');(p/'flow.svg').write_text('\n'.join(svg),encoding='utf-8');im.save(p/'flow.png')
