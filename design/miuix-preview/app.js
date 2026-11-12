/* Local, simulated interaction preview. No audio, source service, or account is contacted. */
(() => {
  'use strict';
  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => [...root.querySelectorAll(s)];
  const params = new URLSearchParams(location.search);
  const icon = (name, extra = '') => `<svg class="icon ${extra}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const tracks = [
    {id:'light', title:'微光之间', artist:'Kinara Sessions', album:'午后留白', art:'flower', duration:224, source:'网易云', local:true},
    {id:'tide', title:'潮汐慢慢', artist:'Kinara Sessions', album:'沿海公路', art:'tide', duration:238, source:'QQ音乐', local:true},
    {id:'sunset', title:'日落以后', artist:'Kinara Sessions', album:'晚风来信', art:'sunset', duration:196, source:'网易云', local:true},
    {id:'moon', title:'月亮不说话', artist:'Kinara Sessions', album:'夜航', art:'moon', duration:254, source:'酷狗', local:false},
    {id:'field', title:'风经过旷野', artist:'Kinara Sessions', album:'漫游指南', art:'field', duration:209, source:'酷我', local:false},
    {id:'blue', title:'城市的另一面', artist:'Kinara Sessions', album:'蓝色时刻', art:'blue', duration:212, source:'哔哩哔哩', local:false},
    {id:'flower', title:'与花逝去的我', artist:'归尘回梦', album:'与花逝去的我', art:'flower', duration:224, source:'网易云', local:false, noLyrics:true}
  ];
  const demoLyrics = ['风把光揉进了窗边','午后慢慢落在指尖','走过安静的长街','听见微小的心愿','把微光留在你身边','让时光慢一点再慢一点','所有未说出的想念','都在此刻轻轻浮现'];
  const routes = [
    {id:'music', title:'音乐', icon:'music', subtitle:'你的音乐，始终在这里'},
    {id:'search', title:'搜索', icon:'search', subtitle:'一首喜欢的歌，值得被找到'},
    {id:'playlists', title:'歌单', icon:'playlists', subtitle:'把喜欢，整理成自己的形状'},
    {id:'downloads', title:'下载', icon:'download', subtitle:'离线时，也有音乐陪伴'},
    {id:'settings', title:'设置', icon:'settings', subtitle:'让 Kinara，更像你的 Kinara'}
  ];
  const saved = (() => {try{return JSON.parse(localStorage.getItem('kinara-miuix-design') || '{}');}catch{return {};}})();
  const allowed = (value, values, fallback) => values.includes(value) ? value : fallback;
  const state = {
    route:allowed(params.get('route'), routes.map(r=>r.id),'music'),
    theme:allowed(params.get('theme') || saved.theme, ['light','dark','industrial'], 'light'),
    surface:allowed(saved.surface,['solid','glass'],'solid'),
    lyrics:allowed(saved.lyrics,['classic','glow','dynamic'],'glow'),
    style:allowed(params.get('style') || saved.style,['lumiere','sonnet','tempera','auto'],'lumiere'),
    quality:allowed(saved.quality,['low','balanced','full'],'balanced'),
    reduceMotion:!!saved.reduceMotion, automaticMatch:true, gapless:true, coverSync:true,
    track:'light', playing:false, position:42, tab:'songs', query:'', searchType:'songs',
    favorites:new Set(['light','moon']), queue:tracks.slice(0,6).map(t=>t.id),
    playerOpen:false, playerPage:'cover', immersive:false, shuffle:false, downloaded:['light','tide','sunset'],
    playlist:'', toastTimer:null, sheetTrack:null, previewVisible:true
  };
  let frame = null, frameReady = false, frameDispose = null, pointerX = null, ignoreFlipUntil = 0, resumePage = 'cover', fullscreenOwned = false;
  const current = () => tracks.find(t=>t.id===state.track) || tracks[0];
  const time = seconds => `${Math.floor(Math.max(0,seconds)/60)}:${String(Math.floor(Math.max(0,seconds)%60)).padStart(2,'0')}`;
  const art = t => `assets/${t.art}.svg`;
  const button = (name, action, label, attrs='') => `<button class="icon-button" data-action="${action}" aria-label="${label}" ${attrs}>${icon(name)}</button>`;
  const persist = () => {try{localStorage.setItem('kinara-miuix-design', JSON.stringify({theme:state.theme,surface:state.surface,lyrics:state.lyrics,style:state.style,quality:state.quality,reduceMotion:state.reduceMotion}));}catch{}};
  const notifyParent = () => {if(parent !== window)parent.postMessage({type:'kinara-preview-status', route:state.route, theme:state.theme, player:state.playerOpen, immersive:state.immersive}, location.protocol==='file:'?'*':location.origin);};
  function theme(value) {
    state.theme = allowed(value,['light','dark','industrial'],state.theme);
    document.body.dataset.theme = state.theme;
    document.body.dataset.surface = state.surface;
    document.body.dataset.motion = state.reduceMotion?'reduced':'full';
    persist();
    notifyParent();
  }
  function toast(message) {
    clearTimeout(state.toastTimer);
    $('#toast').textContent=message; $('#toast').classList.add('visible');
    state.toastTimer=setTimeout(()=>$('#toast').classList.remove('visible'),2400);
  }
  function navigation() {
    $('#rail-links').innerHTML=routes.map(r=>`<button class="rail-link ${r.id===state.route?'selected':''}" data-route="${r.id}" ${r.id===state.route?'aria-current="page"':''}>${icon(r.icon)}<span>${r.title}</span></button>`).join('');
    $('#bottom-navigation').innerHTML=routes.map(r=>`<button class="bottom-nav-item ${r.id===state.route?'selected':''}" data-route="${r.id}" ${r.id===state.route?'aria-current="page"':''}><span class="nav-icon">${icon(r.icon)}</span><span>${r.title}</span></button>`).join('');
    const route=routes.find(r=>r.id===state.route);
    $('#page-title').textContent=route.title;
    $('#page-eyebrow').textContent=route.subtitle;
    $('.header-actions [data-action=search]').hidden=state.route==='search';
  }
  function trackRow(t, source=false) {
    return `<div class="song-row ${t.id===state.track?'playing':''}" data-track="${t.id}"><button class="song-select" data-action="play-song" data-id="${t.id}" aria-label="播放 ${t.title}"><img class="cover" src="${art(t)}" alt="${t.album} 封面"><span class="track-info"><span class="track-title">${t.title}${source?`<span class="source-pill">${t.source}</span>`:''}</span><span class="track-subtitle">${t.artist} · ${t.album}</span></span></button>${t.id===state.track?'<span class="equalizer" aria-hidden="true"><i></i><i></i><i></i></span>':`<span class="track-duration">${time(t.duration)}</span>`}${button('more','song-menu',`${t.title} 的更多操作`,`data-id="${t.id}"`)}</div>`;
  }
  function miniPlayer() {
    const t=current();
    $('#mini-player').innerHTML=`<div class="mini-player"><button class="mini-track" data-action="open-player" aria-label="展开播放页"><img src="${art(t)}" alt=""><span class="track-info"><strong>${t.title}</strong><small>${t.artist}</small></span></button><button class="icon-button" data-action="toggle-play" data-play aria-label="${state.playing?'暂停':'播放'}">${icon(state.playing?'pause':'play')}</button>${button('queue','queue','播放队列')}<span class="mini-progress"><i data-progress-bar></i></span></div>`;
    renderPanel();
  }
  const timeline = () => `<div class="timeline"><input type="range" class="seek-input" min="0" max="${current().duration}" value="${state.position}" step="0.05" data-seek aria-label="播放进度"><div class="timeline-times"><span data-time>${time(state.position)}</span><span>${time(current().duration)}</span></div></div>`;
  const transport = () => `<div class="transport"><button class="icon-button edge ${state.shuffle?'active':''}" data-action="shuffle" aria-label="随机播放" aria-pressed="${state.shuffle}">${icon('shuffle')}</button>${button('prev','previous','上一首')}<button class="play-main" data-action="toggle-play" data-play aria-label="${state.playing?'暂停':'播放'}">${icon(state.playing?'pause':'play')}</button>${button('next','next','下一首')}${button('queue','queue','播放队列','class="edge"')}</div>`;
  function renderPanel() {
    const t=current();
    $('#listening-panel').innerHTML=`<div class="panel-heading"><span class="kicker">正在播放</span>${button('expand','open-player','展开播放页')}</div><button data-action="open-player" aria-label="展开播放页" style="padding:0"><img class="panel-art" src="${art(t)}" alt="${t.album} 封面"></button><div class="panel-title-line"><h2>${t.title}</h2><button class="icon-button ${state.favorites.has(t.id)?'active':''}" data-action="favorite" aria-label="${state.favorites.has(t.id)?'取消喜欢':'喜欢'}">${icon('heart')}</button></div><p class="panel-artist">${t.artist}</p>${timeline()}${transport()}<div class="panel-lyric">${t.noLyrics?'<p>暂无匹配歌词</p>':`<p>${demoLyrics[3]}</p><p class="current">${demoLyrics[4]}</p><p>${demoLyrics[5]}</p>`}</div><button class="panel-visual" data-action="dynamic"><span>${icon('visual')}进入动态视界</span>${icon('arrow')}</button>`;
    updateClockUI();
  }
  function renderMusic() {
    const t=tracks[0];
    let list='';
    if(state.tab==='songs')list=`<div class="list-topline"><span>${tracks.length} 首歌曲</span><button data-action="play-all">${icon('play')}播放全部</button></div>${tracks.map(t=>trackRow(t)).join('')}`;
    else if(state.tab==='albums')list=`<div class="playlist-grid">${tracks.slice(0,6).map(t=>`<button class="album-tile card" data-action="album" data-id="${t.id}" style="padding:12px"><img class="cover" src="${art(t)}" alt="${t.album}"><strong>${t.album}</strong><small>${t.artist}</small></button>`).join('')}</div>`;
    else list=`<button class="preference card" data-action="artist"><span class="preference-icon">${icon('music')}</span><span class="preference-label"><strong>Kinara Sessions</strong><small>6 首歌曲 · 原创预览曲目</small></span>${icon('arrow')}</button><button class="preference card" data-action="play-song" data-id="flower"><span class="preference-icon">${icon('music')}</span><span class="preference-label"><strong>归尘回梦</strong><small>1 首歌曲</small></span>${icon('arrow')}</button>`;
    $('#page-content').innerHTML=`<section class="continue-card card"><div class="continue-copy"><span class="kicker">CONTINUE LISTENING</span><h2>${t.title}</h2><p>${t.artist} · ${t.album}</p><button class="primary-button" data-action="continue">${icon('play')}继续聆听</button></div><img class="continue-art" src="${art(t)}" alt="午后留白 封面"></section><div class="section-heading"><h2>最近常听</h2><button class="text-button" data-action="all-recent">查看全部${icon('arrow')}</button></div><div class="album-strip">${tracks.slice(1,5).map(t=>`<button class="album-tile" data-action="album" data-id="${t.id}"><img class="cover" src="${art(t)}" alt="${t.album} 封面"><strong>${t.album}</strong><small>${t.artist}</small></button>`).join('')}</div><div class="library-tabs" role="tablist" aria-label="音乐分类">${[['songs','歌曲'],['albums','专辑'],['artists','歌手']].map(([id,title])=>`<button role="tab" aria-selected="${state.tab===id}" class="${state.tab===id?'selected':''}" data-tab="${id}">${title}</button>`).join('')}<button class="sort-button" data-action="sort" aria-label="排序">${icon('sort')}</button></div><div id="library-list">${list}</div>`;
  }
  function renderSearch() {
    $('#page-content').innerHTML=`<label class="search-field">${icon('search')}<input id="search-input" type="search" autocomplete="off" placeholder="歌曲、歌手、专辑" aria-label="搜索歌曲、歌手、专辑" value="${esc(state.query)}">${state.query?button('close','clear-search','清空搜索'):''}</label><div id="search-results"></div>`;
    renderSearchResults();
  }
  function renderSearchResults() {
    const q=state.query.trim().toLocaleLowerCase();
    if(!q){$('#search-results').innerHTML=`<div class="section-heading"><h2>最近搜索</h2><button class="text-button" data-action="clear-history">清空</button></div><div class="chip-row" id="search-history">${['与花逝去的我','微光之间','Kinara Sessions'].map(t=>`<button class="chip" data-search="${t}">${t}</button>`).join('')}</div><div class="section-heading"><h2>试试这些</h2></div><div class="card search-result-card">${tracks.slice(0,4).map(t=>trackRow(t,true)).join('')}</div>`;return;}
    const results=tracks.filter(t=>`${t.title} ${t.artist} ${t.album}`.toLocaleLowerCase().includes(q));
    $('#search-results').innerHTML=`<div class="chip-row">${[['songs','单曲'],['albums','专辑'],['artists','歌手']].map(([id,label])=>`<button class="chip ${state.searchType===id?'active':''}" data-search-type="${id}">${label}</button>`).join('')}</div><p class="result-caption">${results.length ? `找到 ${results.length} ${state.searchType==='songs'?'首歌曲':state.searchType==='albums'?'张专辑':'位歌手'} · 播放时自动匹配可用音源`:'换个关键词，再找找看'}</p>${results.length?`<div class="card search-result-card">${state.searchType==='songs'?results.map(t=>trackRow(t,true)).join(''):state.searchType==='albums'?results.map(t=>`<button class="preference" data-action="album" data-id="${t.id}"><img class="cover" width="45" height="45" src="${art(t)}" alt=""><span class="preference-label"><strong>${t.album}</strong><small>${t.artist}</small></span>${icon('arrow')}</button>`).join(''):[...new Set(results.map(t=>t.artist))].map(a=>`<button class="preference" data-search="${a}"><span class="preference-icon">${icon('music')}</span><span class="preference-label"><strong>${a}</strong><small>查看歌曲与专辑</small></span>${icon('arrow')}</button>`).join('')}</div>`:`<div class="empty-state card">${icon('search')}<h3>暂时没有找到</h3><p>试试歌曲名、歌手名，或更短的关键词。</p><button class="text-button" data-search="微光">搜索「微光」</button></div>`}`;
  }
  const playlistData = [
    {id:'favorites',title:'我喜欢的音乐',ids:['light','moon'],count:2},
    {id:'afternoon',title:'把午后留给自己',ids:['light','field','sunset','tide'],count:4},
    {id:'night',title:'夜晚也有光',ids:['moon','blue','tide','light'],count:4},
    {id:'travel',title:'向海边出发',ids:['tide','sunset','field','blue'],count:4}
  ];
  function renderPlaylists() {
    const p=playlistData.find(p=>p.id===state.playlist);
    if(p){const ids=p.id==='favorites'?[...state.favorites]:p.ids;$('#page-content').innerHTML=`<button class="text-button" data-action="playlist-back">${icon('back')}所有歌单</button><div class="section-heading"><h2>${p.title}</h2><button class="text-button" data-action="playlist-play" data-id="${p.id}">${icon('play')}播放</button></div><div class="card search-result-card">${ids.map(id=>trackRow(tracks.find(t=>t.id===id))).join('')||'<div class="empty-state"><h3>还没有喜欢的歌曲</h3><p>轻点歌曲旁的爱心，收集喜欢。</p></div>'}</div>`;return;}
    $('#page-content').innerHTML=`<button class="import-card card" data-action="import"><span class="preference-icon">${icon('upload')}</span><span><strong>带上你喜欢的歌</strong><small>导入歌单，继续熟悉的旋律</small></span>${icon('arrow')}</button><div class="section-heading"><h2>我的歌单</h2><button class="text-button" data-action="create-playlist">${icon('plus')}新建</button></div><div class="playlist-grid">${playlistData.map(p=>`<button class="playlist-card card" data-playlist="${p.id}"><div class="playlist-mosaic">${p.ids.slice(0,2).map(id=>{const t=tracks.find(t=>t.id===id);return `<img src="${art(t)}" alt="">`;}).join('')}</div><h3>${p.title}</h3><small>${p.id==='favorites'?state.favorites.size:p.count} 首歌曲</small></button>`).join('')}</div>`;
  }
  function renderDownloads() {
    $('#page-content').innerHTML=`<div class="download-summary card"><span class="preference-icon">${icon('download')}</span><div><strong>${state.downloaded.length} 首已下载</strong><p>高品质 · 可离线播放</p></div><span class="download-status">${icon('check')}已完成</span></div><div class="section-heading"><h2>离线音乐</h2><button class="text-button" data-action="download-settings">下载设置${icon('arrow')}</button></div><div class="card search-result-card">${state.downloaded.map(id=>trackRow(tracks.find(t=>t.id===id))).join('')}</div><p class="settings-footnote">音乐下载后会保留歌曲封面和歌词，在没有网络时也能聆听。</p>`;
  }
  function pref(title, detail, action, value='', glyph='', extra='') {
    return `<button class="preference" data-action="${action}" ${extra}>${glyph?`<span class="preference-icon">${icon(glyph)}</span>`:''}<span class="preference-label"><strong>${title}</strong>${detail?`<small>${detail}</small>`:''}</span><span class="preference-value">${value}${icon('arrow')}</span></button>`;
  }
  function switchPref(title,detail,key,glyph='') {
    return `<div class="preference">${glyph?`<span class="preference-icon">${icon(glyph)}</span>`:''}<span class="preference-label"><strong>${title}</strong><small>${detail}</small></span><button class="switch" role="switch" aria-label="${title}" aria-checked="${state[key]}" data-switch="${key}"></button></div>`;
  }
  const styleNames={lumiere:'流光',sonnet:'诗行',tempera:'绘境',auto:'随歌变化'};
  const lyricNames={classic:'经典歌词',glow:'逐字辉光',dynamic:'动态视界'};
  function renderSettings() {
    $('#page-content').innerHTML=`<div class="settings-intro card"><img src="assets/mark.svg" alt="Kinara"><div><h2>随心一点，听得舒服</h2><p>从界面到歌词，找到你喜欢的样子。</p></div>${icon('visual')}</div><div class="settings-layout"><section><p class="group-label">外观与界面</p><div class="card preference-group"><div class="theme-picker">${[['light','浅色'],['dark','深色'],['industrial','工业']].map(([id,label])=>`<button class="theme-option ${state.theme===id?'selected':''}" data-theme-choice="${id}" aria-pressed="${state.theme===id}"><span class="theme-swatch ${id}"><i></i><span><b></b><b></b></span></span>${label}</button>`).join('')}</div>${pref('界面质感','播放器与导航的表面效果','surface',state.surface==='glass'?'液态玻璃':'简洁','album')}</div><p class="group-label">歌词与动态视界</p><div class="card preference-group">${pref('歌词显示','轻点或左右滑动封面切换歌词','lyric-style',lyricNames[state.lyrics],'visual')}${pref('视觉风格','动态视界的画面与排版','visual-style',styleNames[state.style])}${pref('渲染品质','平衡细节与流畅度','quality',{low:'省电',balanced:'均衡',full:'精细'}[state.quality])}${switchPref('减少动态效果','降低镜头与背景的运动幅度','reduceMotion')}</div><button class="preview-button" data-action="dynamic">${icon('visual')}体验动态视界${icon('arrow')}</button></section><section><p class="group-label">播放与音源</p><div class="card preference-group">${switchPref('自动匹配音源','优先使用可播放的高匹配版本','automaticMatch','signal')}${switchPref('无缝播放','预载下一首，减少歌曲之间的等待','gapless')}${switchPref('自动更新封面','联网补全歌曲、专辑与封面信息','coverSync')}${pref('音源优先级','网易云 → QQ → 酷狗 → 酷我 → B站 → 其他','source-order','','queue')}</div><p class="group-label">应用</p><div class="card preference-group">${pref('在线更新','发现新版本后提醒我','updates','已是最新','download')}${pref('存储与缓存','管理本地歌曲、封面与歌词','storage','126 MB','folder')}${pref('关于 Kinara','音乐与你，随时相伴','about','','info')}</div><p class="settings-footnote">横屏视界可在动态视界中主动进入。进入后只保留动态歌词与播放进度，返回即可回到原来的页面。</p></section></div>`;
  }
  function render() {
    navigation();
    ({music:renderMusic,search:renderSearch,playlists:renderPlaylists,downloads:renderDownloads,settings:renderSettings}[state.route])();
    miniPlayer();
    updatePlaybackButtons();
    notifyParent();
  }
  function navigate(route) {
    if(!routes.some(r=>r.id===route))return;
    leaveImmersive(false); state.playerOpen=false;$('#player-screen').hidden=true;$('#app-shell').hidden=false;
    suspendFrame();state.route=route;state.playlist='';render();$('#page-content').scrollTop=0;
  }
  function lyricsMarkup() {
    if(current().noLyrics)return '<div class="player-lyrics"><p>暂无匹配歌词</p></div>';
    const index=Math.min(demoLyrics.length-1,Math.floor((state.position%64)/8));
    return `<div class="player-lyrics ${state.lyrics==='glow'?'glow':''}">${demoLyrics.map((line,i)=>`<p data-lyric-index="${i}" class="${i===index?'current':''}">${line}</p>`).slice(Math.max(0,index-2),Math.min(8,index+3)).join('')}</div>`;
  }
  function controlsMarkup() {
    const t=current();
    return `<div class="player-controls-side"><div class="player-song-line"><h1>${t.title}</h1><button class="icon-button ${state.favorites.has(t.id)?'active':''}" data-action="favorite" aria-label="${state.favorites.has(t.id)?'取消喜欢':'喜欢'}">${icon('heart')}</button></div><p class="muted">${t.artist} · ${t.album}</p>${timeline()}${transport()}<div class="player-extra"><button data-action="lyric-style">${icon('visual')}${lyricNames[state.lyrics]}</button><button data-action="source-info">${icon('signal')}${t.source}</button><button data-action="song-menu" data-id="${t.id}">${icon('more')}更多</button></div></div>`;
  }
  function renderPlayer() {
    const t=current(), dynamic=state.lyrics==='dynamic';
    $('#player-screen').classList.toggle('visual-player',dynamic);
    $('#landscape-entry').hidden=!dynamic;
    $('#player-body').innerHTML=dynamic?`<div class="visual-stage-wrap" id="portrait-stage"></div>${controlsMarkup()}`:`<div class="player-art-side"><button class="player-art-wrap" data-action="flip-player" aria-label="${state.playerPage==='cover'?'切换到歌词，可左右滑动':'切换到封面，可左右滑动'}">${state.playerPage==='cover'?`<img class="player-cover" src="${art(t)}" alt="${t.album} 封面">`:lyricsMarkup()}</button><div class="player-pages" aria-label="封面与歌词"><button class="page-dot ${state.playerPage==='cover'?'selected':''}" data-player-page="cover" aria-label="封面" aria-pressed="${state.playerPage==='cover'}"></button><button class="page-dot ${state.playerPage==='lyrics'?'selected':''}" data-player-page="lyrics" aria-label="歌词" aria-pressed="${state.playerPage==='lyrics'}"></button></div></div>${controlsMarkup()}`;
    if(dynamic)attachFrame($('#portrait-stage'));else suspendFrame();
    updateClockUI();updatePlaybackButtons();
  }
  function openPlayer() {
    state.playerOpen=true;$('#app-shell').hidden=true;$('#player-screen').hidden=false;
    renderPlayer();notifyParent();
  }
  function closePlayer() {
    if(state.immersive){leaveImmersive();return;}
    state.playerOpen=false;$('#player-screen').hidden=true;$('#app-shell').hidden=false;suspendFrame();render();
  }
  function stageTargetOrigin() {return location.protocol==='file:'?'*':location.origin;}
  function sendStage(message) {if(frame?.contentWindow && frameReady)frame.contentWindow.postMessage({type:'kinara-stage',...message},stageTargetOrigin());}
  function stageConfig() {
    sendStage({command:'configure', song:{identity:`kinara-preview-${current().id}`,title:current().title,artist:current().artist,album:current().album,durationMs:current().duration*1000,visualStyle:state.style,quality:state.quality,industrial:state.theme==='industrial',reduceMotion:state.reduceMotion,noLyrics:!!current().noLyrics}});
    sendClock();
  }
  function attachFrame(host) {
    clearTimeout(frameDispose);
    if(!frame){
      frame=document.createElement('iframe');frame.className='folia-frame';frame.src='stage.html';frame.title='动态歌词画面';frame.setAttribute('allow','fullscreen');
      let portal=$('#folia-portal');if(!portal){portal=document.createElement('div');portal.id='folia-portal';portal.className='folia-portal';document.body.append(portal);}portal.replaceChildren(frame);
      frameReady=false;
    }
    const portal=$('#folia-portal');portal.hidden=false;portal.classList.toggle('landscape',state.immersive);
    if(!$('.stage-placeholder',host))host.insertAdjacentHTML('beforeend','<div class="stage-placeholder">正在展开动态视界…</div>');
    if(frameReady){if(document.body.dataset.renderer==='ready')$$('.stage-placeholder').forEach(el=>el.remove());sendStage({command:'foreground',visible:state.previewVisible&&!document.hidden});sendClock();}
  }
  function suspendFrame() {
    sendStage({command:'foreground',visible:false});
    if($('#folia-portal'))$('#folia-portal').hidden=true;
    clearTimeout(frameDispose);
    frameDispose=setTimeout(()=>{sendStage({command:'destroy'});frame?.remove();frame=null;frameReady=false;},6000);
  }
  function enterImmersive() {
    state.lyrics='dynamic';persist();
    if(!state.playerOpen)openPlayer();
    if(state.immersive)return;
    clearTimeout(state.toastTimer);$('#toast').classList.remove('visible');
    resumePage=state.playerPage;state.immersive=true;$('#player-screen').hidden=true;$('#immersive-screen').hidden=false;
    const range=$('#immersive-screen [data-seek]');range.max=current().duration;
    $('.immersive-timeline .time:last-child').textContent=time(current().duration);
    attachFrame($('#immersive-stage'));updateClockUI();notifyParent();
    history.pushState({kinaraImmersive:true},'');
    // Embedded review keeps the desktop/phone frames visible. Standalone can lock after a user gesture.
    if(window===parent && document.documentElement.requestFullscreen && !document.fullscreenElement){
      document.documentElement.requestFullscreen().then(()=>{fullscreenOwned=true;screen.orientation?.lock?.('landscape').catch(()=>{});}).catch(()=>{});
    }
  }
  function leaveImmersive(popHistory=true) {
    if(!state.immersive)return;
    state.immersive=false;state.playerPage=resumePage;$('#immersive-screen').hidden=true;$('#player-screen').hidden=!state.playerOpen;
    if(fullscreenOwned){screen.orientation?.unlock?.();if(document.fullscreenElement)document.exitFullscreen().catch(()=>{});fullscreenOwned=false;}
    if(state.playerOpen)renderPlayer();else suspendFrame();
    if(popHistory&&history.state?.kinaraImmersive)history.back();
    notifyParent();
  }
  function showSheet(title, html) {
    $('#sheet-title').textContent=title;$('#sheet-content').innerHTML=html;
    if(!$('#sheet').open)$('#sheet').showModal();
  }
  const closeSheet = () => $('#sheet').close();
  function showChoices(title,key,options) {
    showSheet(title,options.map(([id,label,detail])=>`<button class="choice-row ${state[key]===id?'selected':''}" data-choice-key="${key}" data-choice-value="${id}"><span><strong>${label}</strong>${detail?`<small>${detail}</small>`:''}</span>${icon('check')}</button>`).join(''));
  }
  function queueSheet() {
    showSheet('播放队列',`<div class="queue-caption"><span>${state.queue.length} 首歌曲</span><span>箭头可移为下一首</span></div>${state.queue.map(id=>{const t=tracks.find(t=>t.id===id);return `<div class="queue-row ${id===state.track?'playing':''}"><button class="song-select" data-action="queue-play" data-id="${id}"><img class="cover" src="${art(t)}" alt=""><span class="track-info"><strong class="track-title">${t.title}</strong><small class="track-subtitle">${t.artist}</small></span></button>${button('upload','move-next','移为下一首',`data-id="${id}"`)}${button('close','remove-queue','从队列移除',`data-id="${id}"`)}</div>`;}).join('')||'<div class="empty-state"><h3>队列是空的</h3><p>选一首喜欢的歌开始吧。</p></div>'}`);
  }
  function songMenu(id) {
    state.sheetTrack=id||state.track;const t=tracks.find(t=>t.id===state.sheetTrack);
    showSheet('歌曲',`<div class="sheet-song"><img class="cover" src="${art(t)}" alt=""><div><h3>${t.title}</h3><small>${t.artist} · ${t.album}</small></div></div><button class="menu-action" data-action="menu-next">${icon('queue')}下一首播放</button><button class="menu-action" data-action="menu-favorite">${icon('heart')}${state.favorites.has(t.id)?'取消喜欢':'加入我喜欢的音乐'}</button><button class="menu-action" data-action="menu-download">${icon('download')}${state.downloaded.includes(t.id)?'已下载 · 查看离线音乐':'下载歌曲'}</button><button class="menu-action" data-action="menu-playlist">${icon('plus')}加入歌单</button><button class="menu-action" data-action="source-info">${icon('signal')}查看音源与版本</button>`);
  }
  function playSong(id,open=false) {
    if(!tracks.some(t=>t.id===id))return;
    state.track=id;state.position=0;state.playing=true;
    if(!state.queue.includes(id))state.queue.push(id);
    if(open)openPlayer();
    render();if(state.playerOpen&&!state.immersive)renderPlayer();
    if(frameReady)stageConfig();
    updatePlaybackButtons();
  }
  function next(delta=1) {
    if(!state.queue.length){state.playing=false;updatePlaybackButtons();return;}
    const index=Math.max(0,state.queue.indexOf(state.track));
    const offset=state.shuffle&&delta>0?1+Math.floor(Math.random()*Math.max(1,state.queue.length-1)):delta;
    playSong(state.queue[(index+offset+state.queue.length)%state.queue.length]);
  }
  function updatePlaybackButtons() {
    document.body.classList.toggle('paused',!state.playing);
    $$('[data-play]').forEach(el=>{el.innerHTML=icon(state.playing?'pause':'play');el.setAttribute('aria-label',state.playing?'暂停':'播放');});
    sendClock();
  }
  function updateClockUI() {
    const progress=Math.min(100,Math.max(0,state.position/current().duration*100));
    $$('[data-time]').forEach(el=>el.textContent=time(state.position));
    $$('[data-seek]').forEach(el=>{if(document.activeElement!==el)el.value=state.position;el.style.setProperty('--progress',`${progress}%`);});
    $$('[data-progress-bar]').forEach(el=>el.style.width=`${progress}%`);
  }
  function sendClock() {
    const visible=state.previewVisible&&!document.hidden&&(state.immersive||(state.playerOpen&&state.lyrics==='dynamic'));
    sendStage({command:'clock',sample:{identity:`kinara-preview-${current().id}`,positionMs:state.position*1000,durationMs:current().duration*1000,speed:1,advancing:state.playing&&visible,validForMs:1000,sentAtMs:Date.now(),delayMs:0,bands:{power:state.playing?.22+.08*Math.sin(state.position*2):0,bass:.12,treble:.05}}});
  }
  function applyChoice(key,value) {
    if(!({lyrics:['classic','glow','dynamic'],style:['lumiere','sonnet','tempera','auto'],quality:['low','balanced','full'],surface:['solid','glass']}[key]||[]).includes(value))return;
    state[key]=value;persist();theme(state.theme);closeSheet();
    if(state.route==='settings')renderSettings();
    if(state.playerOpen&&!state.immersive)renderPlayer();
    if(frameReady)stageConfig();
    toast(key==='lyrics'?`已切换为${lyricNames[value]}`:key==='style'?`已切换为${styleNames[value]}`:'设置已保存');
  }
  function actions(action,element) {
    const id=element?.dataset.id;
    switch(action){
      case 'search':navigate('search');$('#search-input')?.focus();break;
      case 'theme':theme(state.theme==='light'?'dark':'light');render();break;
      case 'toggle-play':state.playing=!state.playing;updatePlaybackButtons();break;
      case 'open-player':openPlayer();break;
      case 'close-player':closePlayer();break;
      case 'continue':state.track='light';state.playing=true;openPlayer();render();break;
      case 'play-song':playSong(id);break;
      case 'queue-play':playSong(id);closeSheet();break;
      case 'previous':state.position>3?(state.position=0,updateClockUI(),sendClock()):next(-1);break;
      case 'next':next();break;
      case 'shuffle':state.shuffle=!state.shuffle;miniPlayer();if(state.playerOpen&&!state.immersive)renderPlayer();toast(state.shuffle?'随机播放已开启':'按队列顺序播放');break;
      case 'queue':queueSheet();break;
      case 'favorite':state.favorites.has(state.track)?state.favorites.delete(state.track):state.favorites.add(state.track);miniPlayer();if(state.playerOpen&&!state.immersive)renderPlayer();toast(state.favorites.has(state.track)?'已加入我喜欢的音乐':'已取消喜欢');break;
      case 'song-menu':songMenu(id);break;
      case 'player-menu':showSheet('播放选项',`<button class="menu-action" data-action="lyric-style">${icon('visual')}歌词显示</button><button class="menu-action" data-action="visual-style">${icon('album')}动态视界风格</button><button class="menu-action" data-action="quality">${icon('speed')}渲染品质</button>${state.lyrics==='dynamic'?`<button class="menu-action" data-action="immersive">${icon('expand')}进入横屏视界</button>`:''}<button class="menu-action" data-action="source-info">${icon('signal')}歌曲音源</button>`);break;
      case 'flip-player':if(performance.now()<ignoreFlipUntil)break;state.playerPage=state.playerPage==='cover'?'lyrics':'cover';renderPlayer();break;
      case 'dynamic':state.lyrics='dynamic';state.playing=true;persist();openPlayer();if(frameReady)stageConfig();break;
      case 'immersive':closeSheet();enterImmersive();break;
      case 'close-sheet':closeSheet();break;
      case 'lyric-style':showChoices('歌词显示','lyrics',[['classic','经典歌词','清晰、专注的逐行歌词'],['glow','逐字辉光','逐字亮起，柔和平滑地跟随进度'],['dynamic','动态视界','多种画面编排，支持进入横屏沉浸']]);break;
      case 'visual-style':showChoices('动态视界风格','style',[['lumiere','流光','光晕、镜头与逐字歌词交织'],['sonnet','诗行','随乐句展开的文字构图'],['tempera','绘境','色彩、笔触与多层歌词空间'],['auto','随歌变化','每首歌曲匹配一种视觉编排']]);break;
      case 'quality':showChoices('渲染品质','quality',[['low','省电','降低渲染负载，适合较低性能设备'],['balanced','均衡','兼顾画面细节与流畅度'],['full','精细','更多画面细节与后期效果']]);break;
      case 'surface':showChoices('界面质感','surface',[['solid','简洁','清晰的纯色卡片'],['glass','液态玻璃','透光的悬浮播放器与柔和高光']]);break;
      case 'source-order':showSheet('音源优先级',`<p class="sheet-description">搜索结果统一展示，播放时优先匹配相同歌曲、歌手和版本。当前音源不可用时自动继续匹配。</p>${['网易云音乐','QQ音乐','酷狗音乐','酷我音乐','哔哩哔哩','其他音源'].map((name,i)=>`<div class="preference"><span class="preference-icon">${i+1}</span><span class="preference-label"><strong>${name}</strong></span></div>`).join('')}`);break;
      case 'source-info':showSheet('歌曲音源',`<p class="sheet-description">${current().title} · ${current().artist}</p><div class="preference"><span class="preference-icon">${icon('check')}</span><span class="preference-label"><strong>${current().source}</strong><small>当前选用版本</small></span></div><button class="menu-action" data-action="source-order">${icon('signal')}查看自动匹配顺序</button><p class="settings-footnote">此预览使用演示数据，不发起真实音源请求。</p>`);break;
      case 'sort':showSheet('歌曲排序',`<button class="choice-row selected" data-sort="default"><span><strong>最近添加</strong><small>新的喜欢，先听到</small></span>${icon('check')}</button><button class="choice-row" data-sort="name"><span><strong>歌曲名称</strong></span>${icon('check')}</button><button class="choice-row" data-sort="artist"><span><strong>歌手名称</strong></span>${icon('check')}</button>`);break;
      case 'all-recent':showSheet('最近常听',tracks.slice(0,6).map(t=>trackRow(t)).join(''));break;
      case 'play-all':state.queue=tracks.map(t=>t.id);playSong(state.queue[0]);toast('已播放全部歌曲');break;
      case 'album':{const t=tracks.find(t=>t.id===id);showSheet(t.album,`<div class="sheet-song"><img class="cover" src="${art(t)}" alt=""><div><h3>${t.album}</h3><small>${t.artist}</small></div></div>${trackRow(t)}<p class="settings-footnote">1 首歌曲 · ${time(t.duration)}</p>`);break;}
      case 'artist':state.query='Kinara Sessions';navigate('search');break;
      case 'clear-search':state.query='';renderSearch();$('#search-input').focus();break;
      case 'clear-history':$('#search-history').innerHTML='<span class="muted" style="font-size:11px">暂无搜索记录</span>';toast('搜索记录已清空');break;
      case 'playlist-back':state.playlist='';renderPlaylists();break;
      case 'playlist-play':{const p=playlistData.find(p=>p.id===id);state.queue=p.id==='favorites'?[...state.favorites]:[...p.ids];if(state.queue.length)playSong(state.queue[0]);break;}
      case 'create-playlist':showSheet('新建歌单','<label class="sheet-description" for="playlist-name">给喜欢的旋律起个名字</label><input class="import-input" id="playlist-name" placeholder="歌单名称" maxlength="30"><div class="import-actions"><button class="primary-button" data-action="save-playlist">创建</button></div>');break;
      case 'save-playlist':{const name=$('#playlist-name').value.trim();if(!name){toast('先给歌单起个名字');break;}playlistData.push({id:`new-${playlistData.length}`,title:esc(name),ids:['light','tide'],count:2});closeSheet();renderPlaylists();toast('歌单已创建');break;}
      case 'import':showSheet('导入歌单','<p class="sheet-description">粘贴歌单链接，将喜欢的歌带到 Kinara。</p><label for="import-url" class="muted" style="font-size:11px">歌单链接</label><input id="import-url" class="import-input" placeholder="https://…" type="url"><div class="import-actions"><button class="secondary-button" data-action="sample-import">体验示例导入</button><button class="primary-button" data-action="import-check">导入</button></div><p class="settings-footnote">HTML 预览仅导入示例数据，不连接账号和在线歌单。</p>');break;
      case 'import-check':if(!/^https?:\/\/[^\s]+$/i.test($('#import-url').value.trim())){toast('请填写完整的歌单链接');break;}actions('sample-import');break;
      case 'sample-import':if(!playlistData.some(p=>p.id==='imported'))playlistData.push({id:'imported',title:'导入的午后歌单',ids:['light','tide','moon','sunset'],count:4});closeSheet();navigate('playlists');toast('示例歌单已导入，封面随歌曲同步');break;
      case 'move-next':{if(id===state.track){toast('这首正在播放');break;}state.queue=state.queue.filter(x=>x!==id);state.queue.splice(Math.max(0,state.queue.indexOf(state.track)+1),0,id);queueSheet();toast('已移为下一首');break;}
      case 'remove-queue':state.queue=state.queue.filter(x=>x!==id);queueSheet();break;
      case 'menu-next':{const target=state.sheetTrack;state.queue=state.queue.filter(x=>x!==target);state.queue.splice(Math.max(0,state.queue.indexOf(state.track)+1),0,target);closeSheet();toast('已添加到下一首');break;}
      case 'menu-favorite':state.favorites.has(state.sheetTrack)?state.favorites.delete(state.sheetTrack):state.favorites.add(state.sheetTrack);closeSheet();render();toast('已更新我喜欢的音乐');break;
      case 'menu-download':if(state.downloaded.includes(state.sheetTrack)){closeSheet();navigate('downloads');}else{state.downloaded.push(state.sheetTrack);closeSheet();toast('示例歌曲已加入离线音乐');}break;
      case 'menu-playlist':showSheet('加入歌单',playlistData.map(p=>`<button class="menu-action" data-add-playlist="${p.id}">${icon('playlists')}${p.title}</button>`).join(''));break;
      case 'download-settings':showSheet('下载设置',`<p class="sheet-description">下载后的歌曲保留封面和歌词。</p><button class="choice-row selected" data-action="close-sheet"><span><strong>高品质</strong><small>优先选择兼顾体积与音质的版本</small></span>${icon('check')}</button><button class="menu-action" data-action="storage">${icon('folder')}查看存储空间</button>`);break;
      case 'updates':showSheet('在线更新',`<div class="empty-state"><img width="60" height="60" src="assets/mark.svg" alt="Kinara"><h3>Kinara</h3><p>你已在使用预览中的最新界面。<br>正式版本将从项目发布页检查更新。</p><button class="primary-button" data-action="check-update">检查更新</button></div>`);break;
      case 'check-update':toast('预览不检查真实版本；正式 App 接入在线更新');break;
      case 'storage':showSheet('存储与缓存',`<p class="sheet-description">本地使用 126 MB</p><div class="preference"><span class="preference-label"><strong>离线音乐</strong></span><span class="preference-value">118 MB</span></div><div class="preference"><span class="preference-label"><strong>封面与歌词缓存</strong></span><span class="preference-value">8 MB</span></div><button class="preview-button" data-action="clear-cache">清理封面与歌词缓存</button>`);break;
      case 'clear-cache':closeSheet();toast('示例缓存已清理，离线歌曲已保留');break;
      case 'about':showSheet('关于 Kinara',`<div class="empty-state"><img src="assets/mark.svg" width="64" height="64" alt="Kinara"><h3>Kinara</h3><p>音乐与你，随时相伴。</p><p>用安静的界面，<br>留住每一个想听歌的瞬间。</p></div>`);break;
    }
  }
  document.addEventListener('click',event=>{
    const el=event.target.closest('button,[data-route]');if(!el)return;
    if(el.dataset.route){navigate(el.dataset.route);return;}
    if(el.dataset.themeChoice){theme(el.dataset.themeChoice);renderSettings();if(frameReady)stageConfig();return;}
    if(el.dataset.tab){state.tab=el.dataset.tab;renderMusic();return;}
    if(el.dataset.search!==undefined){state.query=el.dataset.search;if(state.route!=='search')navigate('search');else renderSearch();return;}
    if(el.dataset.searchType){state.searchType=el.dataset.searchType;renderSearchResults();return;}
    if(el.dataset.playerPage){state.playerPage=el.dataset.playerPage;renderPlayer();return;}
    if(el.dataset.choiceKey){applyChoice(el.dataset.choiceKey,el.dataset.choiceValue);return;}
    if(el.dataset.playlist){state.playlist=el.dataset.playlist;renderPlaylists();return;}
    if(el.dataset.addPlaylist){const p=playlistData.find(p=>p.id===el.dataset.addPlaylist);if(p.id==='favorites')state.favorites.add(state.sheetTrack);else{if(!p.ids.includes(state.sheetTrack))p.ids.push(state.sheetTrack);p.count=p.ids.length;}closeSheet();toast('已加入歌单');return;}
    if(el.dataset.switch){const key=el.dataset.switch;if(!['reduceMotion','automaticMatch','gapless','coverSync'].includes(key))return;state[key]=!state[key];el.setAttribute('aria-checked',state[key]);persist();theme(state.theme);if(frameReady)stageConfig();return;}
    if(el.dataset.sort){const key=el.dataset.sort;closeSheet();if(key==='name')tracks.sort((a,b)=>a.title.localeCompare(b.title,'zh-CN'));else if(key==='artist')tracks.sort((a,b)=>a.artist.localeCompare(b.artist,'zh-CN'));else tracks.sort((a,b)=>(a.id==='light'?-1:b.id==='light'?1:0));renderMusic();toast('歌曲顺序已更新');return;}
    if(el.dataset.action)actions(el.dataset.action,el);
  });
  document.addEventListener('input',event=>{
    if(event.target.id==='search-input'){state.query=event.target.value;renderSearchResults();}
    if(event.target.matches('[data-seek]')){state.position=Number(event.target.value);updateClockUI();sendClock();}
  });
  document.addEventListener('keydown',event=>{
    if(event.key==='Escape'&&!$('#sheet').open){if(state.immersive){event.preventDefault();leaveImmersive();}else if(state.playerOpen)closePlayer();}
    if(event.code==='Space'&&!['INPUT','TEXTAREA','BUTTON','SELECT'].includes(document.activeElement?.tagName)&&!$('#sheet').open){event.preventDefault();state.playing=!state.playing;updatePlaybackButtons();}
    if(state.immersive&&['ArrowLeft','ArrowRight'].includes(event.key)&&!event.target.matches('[data-seek]')){event.preventDefault();state.position=Math.max(0,Math.min(current().duration,state.position+(event.key==='ArrowRight'?5:-5)));updateClockUI();sendClock();}
  });
  $('#player-body').addEventListener('pointerdown',event=>{if(event.target.closest('.player-art-wrap'))pointerX=event.clientX;});
  $('#player-body').addEventListener('pointerup',event=>{if(pointerX!==null&&Math.abs(event.clientX-pointerX)>40){ignoreFlipUntil=performance.now()+350;state.playerPage=state.playerPage==='cover'?'lyrics':'cover';renderPlayer();event.preventDefault();}pointerX=null;});
  $('#player-body').addEventListener('pointercancel',()=>{pointerX=null;});
  $('#player-body').addEventListener('dragstart',event=>{if(event.target.closest('.player-art-wrap'))event.preventDefault();});
  $('#sheet').addEventListener('click',event=>{if(event.target===$('#sheet')){const rect=event.target.getBoundingClientRect();if(event.clientY<rect.top||event.clientX<rect.left||event.clientX>rect.right)closeSheet();}});
  window.addEventListener('popstate',()=>{if(state.immersive)leaveImmersive(false);});
  document.addEventListener('fullscreenchange',()=>{if(fullscreenOwned&&!document.fullscreenElement){fullscreenOwned=false;screen.orientation?.unlock?.();if(state.immersive)leaveImmersive();}});
  document.addEventListener('visibilitychange',()=>{sendStage({command:'foreground',visible:state.previewVisible&&!document.hidden});sendClock();});
  window.addEventListener('message',event=>{
    if(frame&&event.source===frame.contentWindow&&(event.origin===location.origin||location.protocol==='file:'&&event.origin==='null')){
      if(event.data?.type==='kinara-stage-ready'){frameReady=true;stageConfig();sendStage({command:'foreground',visible:state.previewVisible&&!document.hidden});}
      if(event.data?.type==='kinara-stage-status'){
        document.body.dataset.renderer=event.data.status;
        if(event.data.status!=='ready'&&(state.immersive||state.playerOpen&&state.lyrics==='dynamic')){const host=state.immersive?$('#immersive-stage'):$('#portrait-stage');if(host&&!$('.stage-placeholder',host))host.insertAdjacentHTML('beforeend','<div class="stage-placeholder">正在展开动态视界…</div>');}
        if(event.data.status==='ready')$$('.stage-placeholder').forEach(el=>el.remove());
        if(event.data.status==='failed')$$('.stage-placeholder').forEach(el=>{el.classList.add('stage-error');el.innerHTML=`<span>当前浏览器无法显示动态画面。<br>可尝试用新版 Chrome 打开预览。</span><button data-action="lyric-style">切换歌词显示</button>`;});
      }
      if(event.data?.type==='kinara-stage-tap'){state.playing=!state.playing;updatePlaybackButtons();}
      if(event.data?.type==='kinara-stage-back'){if(state.immersive)leaveImmersive();else closePlayer();}
      return;
    }
    if(event.source!==parent||!(event.origin===location.origin||location.protocol==='file:'&&event.origin==='null')||event.data?.type!=='kinara-preview-command')return;
    const {command,value}=event.data;
    if(command==='theme'){theme(value);render();if(state.playerOpen&&!state.immersive)renderPlayer();if(frameReady)stageConfig();}
    if(command==='navigate'){if(value==='player'){leaveImmersive(false);openPlayer();}else if(value==='dynamic'){leaveImmersive(false);actions('dynamic');}else if(value==='landscape'){state.playing=true;enterImmersive();}else navigate(value);}
    if(command==='visible'){state.previewVisible=!!value;sendStage({command:'foreground',visible:state.previewVisible&&!document.hidden});sendClock();}
    if(command==='style'&&['lumiere','sonnet','tempera','auto'].includes(value)){state.style=value;persist();if(frameReady)stageConfig();}
  });
  let previousTick=performance.now();
  setInterval(()=>{
    const now=performance.now(),delta=Math.min(1,(now-previousTick)/1000);previousTick=now;
    if(state.playing&&state.previewVisible&&!document.hidden){state.position+=delta;if(state.position>=current().duration){next();return;}}
    updateClockUI();sendClock();
  },250);
  theme(state.theme);render();
  if(params.get('page')==='player')openPlayer();
  if(params.get('page')==='dynamic'){actions('dynamic');}
  if(params.get('page')==='landscape'){state.playing=true;enterImmersive();}
})();
