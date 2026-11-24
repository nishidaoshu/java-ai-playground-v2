"""Local-only Chinese embedding API. BGE CLS pooling + L2 normalization, ONNX CPU int8.
No network/model downloads at runtime. No external key or hosted embedding calls.
"""
import argparse,json,threading
from pathlib import Path
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

parser=argparse.ArgumentParser()
parser.add_argument('--model-dir',required=True)
parser.add_argument('--port',type=int,default=18083)
args=parser.parse_args()
model=Path(args.model_dir)
tokenizer=Tokenizer.from_file(str(model/'tokenizer.json'))
tokenizer.enable_truncation(max_length=512)
tokenizer.enable_padding()
options=ort.SessionOptions();options.intra_op_num_threads=4
session=ort.InferenceSession(str(model/'onnx/model_quantized.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
gate=threading.Lock()

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*a):pass
    def reply(self,status,data):
        body=json.dumps(data,ensure_ascii=False).encode('utf-8');self.send_response(status)
        self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_GET(self):
        self.reply(200,{'application':'java-ai-v2-embedding','model':'bge-small-zh-v1.5','dimensions':512,'provider':'CPUExecutionProvider','quantization':'int8'})
    def do_POST(self):
        if self.path!='/v1/embeddings':return self.reply(404,{'error':'not found'})
        try:
            size=int(self.headers.get('Content-Length','0'))
            if size>1048576:return self.reply(413,{'error':'too large'})
            data=json.loads(self.rfile.read(size));texts=data['input'];texts=[texts] if isinstance(texts,str) else texts
            if not texts or len(texts)>32:raise ValueError()
            with gate:
                enc=tokenizer.encode_batch(texts)
                values={'input_ids':np.array([e.ids for e in enc],dtype=np.int64),'attention_mask':np.array([e.attention_mask for e in enc],dtype=np.int64),'token_type_ids':np.array([e.type_ids for e in enc],dtype=np.int64)}
                outputs=session.run(None,{v.name:values[v.name] for v in session.get_inputs()})
                embeddings=outputs[0][:,0,:].astype(np.float32)
                embeddings/=np.maximum(np.linalg.norm(embeddings,axis=1,keepdims=True),1e-12)
            self.reply(200,{'object':'list','model':'bge-small-zh-v1.5','data':[{'object':'embedding','index':i,'embedding':v.tolist()} for i,v in enumerate(embeddings)],'usage':{'prompt_tokens':sum(sum(e.attention_mask) for e in enc),'total_tokens':sum(sum(e.attention_mask) for e in enc)}})
        except Exception:self.reply(400,{'error':'embedding request failed'})

ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()

