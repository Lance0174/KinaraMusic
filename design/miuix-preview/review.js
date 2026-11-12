(() => {
  'use strict';
  const frames=[...document.querySelectorAll('.frame-viewport iframe')];
  const layout=document.querySelector('#preview-layout');
  let view='both',theme='light',route='music',style='lumiere';
  const send=(frame,command,value)=>{if(frame.contentWindow)frame.contentWindow.postMessage({type:'kinara-preview-command',command,value},location.protocol==='file:'?'*':location.origin);};
  function scale(){document.querySelectorAll('.frame-viewport').forEach(viewport=>{if(viewport.clientWidth===0)return;const width=Number(viewport.dataset.width),height=Number(viewport.dataset.height),ratio=viewport.clientWidth/width;viewport.style.height=`${height*ratio}px`;viewport.querySelector('iframe').style.transform=`scale(${ratio})`;});}
  const observer=new ResizeObserver(scale);document.querySelectorAll('.frame-viewport').forEach(el=>observer.observe(el));
  function visibility(){frames.forEach(frame=>{const active=frame.id==='landscape-preview'?view==='landscape':view==='both'||view===(frame.id==='phone-preview'?'phone':'desktop');send(frame,'visible',active);});}
  function setView(value){view=value;layout.dataset.view=view;document.querySelector('#landscape-device').hidden=view!=='landscape';document.querySelector('#landscape-tools').hidden=view!=='landscape';document.querySelectorAll('[data-view]').forEach(button=>{if(button===layout)return;button.classList.toggle('active',button.dataset.view===view);button.setAttribute('aria-pressed',button.dataset.view===view);});document.querySelector('#route-picker').disabled=view==='landscape';if(view==='landscape'){const frame=document.querySelector('#landscape-preview');if(!frame.src)frame.src=frame.dataset.src;}requestAnimationFrame(scale);visibility();}
  document.querySelectorAll('.view-picker button').forEach(button=>button.addEventListener('click',()=>setView(button.dataset.view)));
  document.querySelectorAll('.theme-controls button').forEach(button=>button.addEventListener('click',()=>{theme=button.dataset.theme;document.body.dataset.reviewTheme=theme;document.querySelectorAll('.theme-controls button').forEach(b=>{b.classList.toggle('active',b===button);b.setAttribute('aria-pressed',b===button);});frames.forEach(frame=>send(frame,'theme',theme));document.querySelectorAll('.device-label a').forEach(a=>a.href=`app.html?theme=${theme}`);}));
  document.querySelector('#route-picker').addEventListener('change',event=>{route=event.target.value;frames.filter(f=>f.id!=='landscape-preview').forEach(frame=>send(frame,'navigate',route));});
  document.querySelector('#style-picker').addEventListener('change',event=>{style=event.target.value;send(document.querySelector('#landscape-preview'),'style',style);});
  frames.forEach(frame=>frame.addEventListener('load',()=>{if(!frame.getAttribute('src'))return;send(frame,'theme',theme);if(frame.id==='landscape-preview')send(frame,'style',style);else if(route!=='music')send(frame,'navigate',route);visibility();scale();}));
  window.addEventListener('resize',scale);scale();
})();
