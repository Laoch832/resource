from pathlib import Path
import subprocess,wave,array,hashlib,json,time
r=Path(__file__).resolve().parent
rows=[]
for source in sorted((r/'libmpegh/smoke_test_suite/inp').glob('*')):
 out=r/('matrix-'+source.name+'.wav');log=r/('matrix-'+source.name+'.log')
 start=time.monotonic()
 with log.open('wb') as f:
  result=subprocess.run([str(r/'libmpegh-build/ia_mpeghd_testbench'),'-ifile:'+str(source),'-ofile:'+str(out),'-ibrir:'+str(r/'sony-real-profile.brir'),'-cicp:16'],stdout=f,stderr=subprocess.STDOUT,timeout=120)
 with wave.open(str(out)) as wav:
  pcm=wav.readframes(wav.getnframes());values=array.array('h',pcm)
  if result.returncode!=0 or wav.getnchannels()!=2 or wav.getframerate()!=48000 or wav.getnframes()<48000 or not any(values):raise RuntimeError('Failed '+source.name)
  rows.append(dict(source=source.name,channels=wav.getnchannels(),rate=wav.getframerate(),frames=wav.getnframes(),pcm_sha256=hashlib.sha256(pcm).hexdigest(),rms=(sum(v*v for v in values)/len(values))**.5,seconds=round(time.monotonic()-start,3)))
  print('PASS',source.name,flush=True)
(r/'codec-validation.json').write_text(json.dumps(rows,indent=2))
