from pathlib import Path
import requests,hashlib,json
p=Path(r'D:\.work\jianli\.java-ai-runtime-v1\models\bge-small-zh-v1.5');p.mkdir(parents=True,exist_ok=True)
s=requests.Session();s.proxies={'http':'http://127.0.0.1:7897','https':'http://127.0.0.1:7897'}
repo='Xenova/bge-small-zh-v1.5'
rev='75c43b069aac4d136ba6bc1122f995fedcfd2781'
manifest={'repo':repo,'revision':rev,'files':{}}
for name in ['tokenizer.json','tokenizer_config.json','config.json','onnx/model_quantized.onnx']:
 dest=p/name;dest.parent.mkdir(exist_ok=True)
 if not dest.exists():
  r=s.get('https://huggingface.co/'+repo+'/resolve/'+rev+'/'+name,timeout=180,stream=True);r.raise_for_status()
  with dest.open('wb') as f:
   for b in r.iter_content(1024*1024):f.write(b)
 manifest['files'][name]=hashlib.sha256(dest.read_bytes()).hexdigest();print(name+' ready',flush=True)
(p/'manifest.json').write_text(json.dumps(manifest,indent=2))
