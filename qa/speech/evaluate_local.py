"""Evaluate the Android Whisper/VAD settings on local audio; never uploads input.
Requires sherpa-onnx==1.13.8 and numpy. Use ffmpeg to supply 16 kHz mono PCM WAV.
"""
import argparse,json,wave
from pathlib import Path
import numpy as np
import sherpa_onnx

def boundary(samples):
    if len(samples)<17*16000:return len(samples)
    width=1600;quiet=min(.0003,float(np.mean(samples.astype('float64')**2))*.03);cut=len(samples)
    for at in range(len(samples)-3*16000,len(samples)-width+1,width):
        energy=float(np.mean(samples[at:at+width].astype('float64')**2))
        if energy<quiet:quiet=energy;cut=at+width//2
    return cut

def main():
    p=argparse.ArgumentParser();p.add_argument('audio');p.add_argument('--models',required=True);p.add_argument('--prefix',default='turbo');p.add_argument('--vad',required=True);p.add_argument('--language',default='auto');p.add_argument('--output',required=True);args=p.parse_args()
    root=Path(args.models);name=args.prefix
    model=sherpa_onnx.OfflineRecognizer.from_whisper(encoder=str(root/f'{name}-encoder.int8.onnx'),decoder=str(root/f'{name}-decoder.int8.onnx'),tokens=str(root/f'{name}-tokens.txt'),language='' if args.language=='auto' else args.language,num_threads=3,enable_segment_timestamps=True)
    config=sherpa_onnx.VadModelConfig();config.silero_vad.model=args.vad;config.silero_vad.threshold=.5;config.silero_vad.min_silence_duration=.35;config.silero_vad.min_speech_duration=.15;config.silero_vad.max_speech_duration=20;config.sample_rate=16000
    vad=sherpa_onnx.VoiceActivityDetector(config,buffer_size_in_seconds=30)
    with wave.open(args.audio) as f:
        if f.getframerate()!=16000 or f.getnchannels()!=1 or f.getsampwidth()!=2:raise ValueError('Use 16 kHz mono PCM16 WAV')
        audio=np.frombuffer(f.readframes(f.getnframes()),dtype='<i2').astype('float32')/32768
    chosen=args.language
    if chosen=='auto':
        candidates=[]
        for at in range(0,len(audio),320000):
            samples=audio[at:at+320000];vad.reset()
            for n in range(0,len(samples),512):vad.accept_waveform(samples[n:n+512])
            vad.flush();voiced=0
            while not vad.empty():
                part=vad.front;voiced+=len(part.samples);vad.pop()
            if voiced>=min(32000,len(samples)//2):
                candidates.append((voiced,samples));candidates.sort(key=lambda item:-item[0]);candidates=candidates[:3]
        votes={};total=0
        for voiced,samples in candidates:
            stream=model.create_stream();stream.accept_waveform(16000,samples);model.decode_stream(stream);lang=stream.result.lang;total+=voiced;votes[lang]=votes.get(lang,0)+voiced
        chosen=max(votes,key=votes.get) if votes else ''
        if not chosen or votes[chosen]<total*.6:raise ValueError('Spoken language ambiguous; choose manually')
        print('Dominant language:',chosen,'votes:',votes,flush=True)
        del model
        model=sherpa_onnx.OfflineRecognizer.from_whisper(encoder=str(root/f'{name}-encoder.int8.onnx'),decoder=str(root/f'{name}-decoder.int8.onnx'),tokens=str(root/f'{name}-tokens.txt'),language=chosen,num_threads=3,enable_segment_timestamps=True)
    result=[];start=0;windows=0
    while start<len(audio):
        samples=audio[start:start+320000];count=boundary(samples) if len(audio)-start>320000 else len(samples);samples=samples[:count];vad.reset()
        for at in range(0,count,512):vad.accept_waveform(samples[at:at+512])
        vad.flush();regions=[]
        while not vad.empty():
            part=vad.front;a=max(0,part.start-4800);b=min(count,part.start+len(part.samples)+4800)
            if b>a:
                if regions and a-regions[-1][1]<=8000:regions[-1][1]=max(regions[-1][1],b)
                else:regions.append([a,b])
            vad.pop()
        for a,b in regions:
            spoken=samples[a:b]
            def decode(data):
                stream=model.create_stream();stream.accept_waveform(16000,data);model.decode_stream(stream);return stream.result
            def pad(data):return np.pad(data,(0,min(27*16000,len(data)+max(48000,len(data)//3))-len(data)))
            r=decode(pad(spoken) if chosen=='ar' else spoken)
            times=r.segment_timestamps or [0];durations=r.segment_durations or [len(spoken)/16000];texts=r.segment_texts or [r.text]
            offset=(start+a)/16000;limit=(start+b)/16000
            for t,d,text in zip(times,durations,texts):
                begin=max(offset,offset+t);end=min(limit,offset+t+d)
                if text.strip() and end>begin:result.append(dict(start=begin,end=end,text=text.strip(),language=r.lang))
            windows+=1
        start+=count
        Path(args.output).write_text(json.dumps(dict(segments=result,windows=windows),ensure_ascii=False,indent=2))
        print(f'{start/16000:.1f}/{len(audio)/16000:.1f}s; cues={len(result)}',flush=True)
if __name__=='__main__':main()
