import subprocess,time,json,traceback
from pathlib import Path

out=Path('artifacts');out.mkdir(exist_ok=True)
report={'installed':{},'launched':{},'probe':None,'limitations':['Bluetooth hardware, real user ear scan, authenticated NetEase catalogue playback and service availability require device testing.']}

def command(args,timeout=180,check=True):
    r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout)
    if check and r.returncode:raise RuntimeError(str(args)+'\n'+r.stdout[-6000:])
    return r.stdout

try:
    report['android_version']=command(['adb','shell','getprop','ro.build.version.release']).strip()
    report['abis']=command(['adb','shell','getprop','ro.product.cpu.abilist']).strip()
    for kind,pkg in [('sound-connect','com.sony.songpal.mdr'),('netease','com.netease.cloudmusic')]:
        apk=next(Path('apk').glob(kind+'*.apk'))
        text=command(['adb','install','--no-incremental','-r',str(apk)])
        (out/(kind+'-install.txt')).write_text(text)
        if 'Success' not in text:raise RuntimeError('Installation did not succeed '+kind)
        report['installed'][pkg]=True
    command(['adb','install','--no-incremental','-r','ci/r1-runtime-probe.apk'])
    command(['adb','logcat','-c'])
    text=command(['adb','shell','am','start','-W','-n','dev.soundconnect.r1.probe/dev.soundconnect.r1.AndroidProbeActivity'])
    (out/'probe-launch.txt').write_text(text)
    deadline=time.monotonic()+240
    while time.monotonic()<deadline:
        text=command(['adb','shell','run-as','dev.soundconnect.r1.probe','cat','files/probe-result.txt'],check=False,timeout=15)
        if text.startswith(('PASS','FAIL')):report['probe']=text.strip();break
        time.sleep(2)
    if not report['probe'] or not report['probe'].startswith('PASS'):raise RuntimeError('Runtime probe '+str(report['probe']))
    entries=json.loads(Path('ci/app-entries.json').read_text())
    for pkg,entry in entries.items():
        command(['adb','shell','am','force-stop',pkg],check=False)
        text=command(['adb','shell','am','start','-W','-n',entry],timeout=90)
        (out/(pkg+'-launch.txt')).write_text(text)
        time.sleep(12)
        pid=command(['adb','shell','pidof',pkg],check=False).strip()
        report['launched'][pkg]={'entry':entry,'process_alive_after_12_seconds':bool(pid),'pid':pid}
        command(['adb','shell','uiautomator','dump','/sdcard/r1-ui.xml'],check=False)
        command(['adb','pull','/sdcard/r1-ui.xml',str(out/(pkg+'-ui.xml'))],check=False)
        with (out/(pkg+'.png')).open('wb') as f:subprocess.run(['adb','exec-out','screencap','-p'],stdout=f,timeout=30,check=False)
        if not pid:raise RuntimeError('Original app process did not remain alive '+pkg)
    report['result']='PASS'
except Exception:
    report['result']='FAIL';report['error']=traceback.format_exc()
finally:
    (out/'runtime-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    (out/'logcat.txt').write_text(command(['adb','logcat','-d'],check=False,timeout=60))
    print(json.dumps(report,ensure_ascii=False,indent=2))
if report['result']!='PASS':raise SystemExit(1)
