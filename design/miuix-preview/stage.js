/* The original fixture is a visual timing demonstration; no copyrighted song lyrics or audio. */
(() => {
  'use strict';
  const origin=location.protocol==='file:'?'*':location.origin;
  const send=message=>parent.postMessage(message,origin);
  const phrases=['风把光揉进了窗边','午后慢慢落在指尖','走过安静的长街','听见微小的心愿','把微光留在你身边','让时光慢一点再慢一点','所有未说出的想念','都在此刻轻轻浮现'];
  let lastStatus='', song=null, configuredAt=0;
  function lines(duration) {
    const list=[];
    for(let start=0,index=0;start<duration;start+=8000,index++){
      const fullText=phrases[index%phrases.length],chars=Array.from(fullText),end=Math.min(duration,start+7400);
      list.push({startTime:start/1000,endTime:end/1000,fullText,words:chars.map((text,i)=>({text,startTime:(start+i*(end-start)/chars.length)/1000,endTime:(start+(i+1)*(end-start)/chars.length)/1000}))});
    }
    return list;
  }
  function sampleStatus() {
    let status=window.KinaraFolia?.displayStatus()||'loading';
    if(status==='loading'&&configuredAt&&performance.now()-configuredAt>15000)status='failed';
    if(status!==lastStatus){lastStatus=status;send({type:'kinara-stage-status',status,mode:window.KinaraFolia?.mode||''});}
  }
  window.addEventListener('message',event=>{
    if(event.source!==parent||!(event.origin===location.origin||location.protocol==='file:'&&event.origin==='null')||event.data?.type!=='kinara-stage')return;
    const {command}=event.data,api=window.KinaraFolia;
    if(!api)return;
    if(command==='configure'){
      const value=event.data.song;
      if(!value||typeof value.identity!=='string'||!Number.isFinite(value.durationMs)||value.durationMs<=0||value.durationMs>600000)return;
      song={...value,coverColors:value.industrial?['#b8b270','#565c44','#202620']:['#bfc9a7','#769e9a','#306968','#c9b98d'],lines:value.noLyrics?[]:lines(value.durationMs)};
      lastStatus='';configuredAt=performance.now();api.setSong(song).then(sampleStatus);sampleStatus();
    }
    if(command==='clock'&&event.data.sample&&typeof event.data.sample.identity==='string'&&Number.isFinite(event.data.sample.positionMs))api.clock(event.data.sample);
    if(command==='foreground')api.setForeground(!!event.data.visible);
    if(command==='destroy')api.destroy();
  });
  // A tap pauses/resumes the simulated clock; landscape never adds a transport overlay.
  let down=null;
  document.addEventListener('pointerdown',event=>{down={x:event.clientX,y:event.clientY};});
  document.addEventListener('pointerup',event=>{if(down&&Math.hypot(event.clientX-down.x,event.clientY-down.y)<10)send({type:'kinara-stage-tap'});down=null;});
  window.addEventListener('keydown',event=>{if(event.code==='Space'){event.preventDefault();send({type:'kinara-stage-tap'});}if(event.key==='Escape')send({type:'kinara-stage-back'});});
  setInterval(sampleStatus,300);
  send({type:'kinara-stage-ready'});
})();
