import React,{useEffect,useLayoutEffect,useRef,useState} from 'react';
import {registerRoot,Composition,useCurrentFrame,useVideoConfig,staticFile,delayRender,continueRender,cancelRender} from 'remotion';
import {Audio} from '@remotion/media';
import {loadFont} from '@remotion/fonts';
import project from './project.json';
import core from './core';
loadFont({family:'Cairo',url:staticFile('Cairo.ttf'),weight:'100 900'});
// The owner's own font for headings and body text when the project uses it; Cairo stays the fallback.
const brand=(project as any).fonts;if(brand){loadFont({family:'MadarHeading',url:staticFile(brand.headingFile),weight:'100 900'});loadFont({family:'MadarBody',url:staticFile(brand.bodyFile),weight:'100 900'});}
const Frame=()=>{const f=useCurrentFrame(),{fps,width,height}=useVideoConfig(),ref=useRef<HTMLCanvasElement>(null),released=useRef(false);const [hold]=useState(()=>delayRender('تحميل صور المشاهد'));const [images,setImages]=useState<Record<string,HTMLImageElement>|null>(null);
useEffect(()=>{let alive=true;const names=[...new Set(project.scenes.flatMap(s=>[s.image,...core.creative.assets(s)]).filter(Boolean))];Promise.all(names.map(name=>new Promise<[string,HTMLImageElement]>((resolve,reject)=>{const img=new Image();img.onload=()=>resolve([name,img]);img.onerror=()=>reject(Error('IMAGE_DECODE'));img.src=staticFile(name);}))).then(entries=>{if(alive){setImages(Object.fromEntries(entries));}}).catch(cancelRender);return()=>{alive=false;};},[hold]);
useLayoutEffect(()=>{if(!images||!ref.current)return;try{core.draw(ref.current.getContext('2d'),project,f/fps,images);if(!released.current){released.current=true;continueRender(hold);}}catch(error){cancelRender(error);}},[f,fps,images]);
return <><canvas ref={ref} width={width} height={height}/>{project.voiceMode!=='none'?<Audio src={staticFile('voice.wav')}/>:null}</>;};
const Root=()=> <Composition id="MadarMotion" component={Frame} durationInFrames={Math.ceil(project.duration*30)} width={project.aspect==='16:9'?1920:1080} height={project.aspect==='9:16'?1920:1080} fps={30}/>;
registerRoot(Root);
