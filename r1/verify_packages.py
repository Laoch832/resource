"""Validate R1 candidates without mistaking structural validation for device playback."""
from pathlib import Path
import hashlib,json,zipfile
from androguard.core.axml import AXMLPrinter
from androguard.core.dex import DEX
from loguru import logger

ROOT=Path(__file__).resolve().parent.parent
A='{http://schemas.android.com/apk/res/android}'
logger.remove()
def sha(data):return hashlib.sha256(data).hexdigest()
def main():
    rows=[];helpers=[]
    for kind,stem,index,changed in [('sony','sound-connect-13.3.0',7,'classes3.dex'),('netease','netease-9.6.08',21,'classes16.dex')]:
        path=ROOT/'r1/build/candidates'/(stem+'-r1-candidate.apk')
        with zipfile.ZipFile(path) as z,zipfile.ZipFile(ROOT/(stem+'-original.apk')) as src:
            assert z.testzip() is None
            preserved={'native':0,'assets':0,'dex':0,'metadata':0}
            for name in src.namelist():
                k='native' if name.startswith('lib/') and name.endswith('.so') else 'assets' if name.startswith('assets/') and not name.endswith('/') else 'dex' if name.startswith('classes') and name.endswith('.dex') else None
                if k and name!=changed:
                    assert sha(src.read(name))==sha(z.read(name)),name
                    preserved[k]+=1
            from build import is_signature_entry
            for name in src.namelist():
                if name.startswith('META-INF/') and not is_signature_entry(name):
                    assert src.read(name)==z.read(name),name
                    preserved['metadata']+=1
            xml=AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj();app=xml.find('application')
            helper=z.read(f'classes{index}.dex');helpers.append(sha(helper));classes={c.get_name() for c in DEX(helper).get_classes()}
            for c in ['HkiProfile','PartitionedConvolver','SoundAudioTrack','MpeghDecoder','MpeghPlayerActivity']:
                assert 'Ldev/soundconnect/r1/'+c+';' in classes
            hooks={}
            if kind=='netease':
                assert app.get(A+'extractNativeLibs')=='true'
                providers={p.get(A+'name'):p for p in app.findall('provider')}
                for n in ['dev.soundconnect.r1.InitProvider','dev.soundconnect.r1.InitProvider$Playback']:assert providers[n].get(A+'exported')=='false'
                names=['Lcom/netease/cloudmusic/media/audioplayer/NCMNativeAudioTrackBuilder;','Lcom/netease/cloudmusic/media/NEAudioDevice;','Lcom/netease/cloudmusic/media/player/nmAudioTrack;']
                for c in DEX(z.read(changed)).get_classes():
                    if c.get_name() in names:hooks[c.get_name()]=sum('Ldev/soundconnect/r1/SoundAudioTrack;' in i.get_output() for m in c.get_methods() for i in m.get_instructions())
                assert sorted(hooks.values())==[1,2,10]
                for abi,build in [('arm64-v8a','libmpegh-android-arm64'),('armeabi-v7a','libmpegh-android-armv7')]:
                    assert z.read('lib/'+abi+'/libr1mpegh_exec.so')==(ROOT/'r1'/build/'ia_mpeghd_testbench').read_bytes()
            rows.append({'file':stem+'-r1.apk','bytes':path.stat().st_size,'sha256':sha(path.read_bytes()),'zip_crc':'PASS','upstream_preserved':preserved,'changed_upstream_dex':changed,'new_helper_dex':index,'helper_sha256':sha(helper),'manifest_label':app.get(A+'label'),'audio_entry_hooks':hooks if kind=='netease' else 2})
    assert helpers[0]==helpers[1],'The paired helper DEX files must be identical'
    (ROOT/'r1/package-verification.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    print(json.dumps(rows,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
