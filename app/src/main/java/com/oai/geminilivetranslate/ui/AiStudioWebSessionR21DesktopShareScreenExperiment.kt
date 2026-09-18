package com.oai.geminilivetranslate.ui

/**
 * Experimental document-start shim for exercising AI Studio's own desktop Share Screen path
 * from Android WebView. This branch is intentionally isolated from main.
 */
object AiStudioWebSessionR21DesktopShareScreenExperiment {
    const val VERSION = "2026-09-18-r21.3-consistent-desktop-profile"
    const val PREVIOUS_VERSION = "2026-09-18-r21.2-desktop-identity-and-viewport"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R21_DESKTOP_SHARE__&&window.__AIS_R21_DESKTOP_SHARE__.version)return;

  const VERSION='2026-09-18-r21.3-consistent-desktop-profile';
  const DESKTOP_UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.179 Safari/537.36';
  const DESKTOP_CSS_WIDTH=980;
  const DESKTOP_SCREEN_WIDTH=1920;
  const DESKTOP_SCREEN_HEIGHT=1080;
  const state={
    installedAt:Date.now(),
    platformOverride:false,
    userAgentOverride:false,
    appVersionOverride:false,
    userAgentDataOverride:false,
    touchOverride:false,
    screenOverride:false,
    pointerOverride:false,
    viewportOverride:false,
    viewportMutationCount:0,
    viewportPolicy:'native-wide-viewport',
    supportedConstraintsOverride:false,
    mediaDevicesPresent:!!navigator.mediaDevices,
    getDisplayMediaAtInstall:!!(navigator.mediaDevices&&typeof navigator.mediaDevices.getDisplayMedia==='function'),
    environmentReports:0
  };

  function safe(v,n){return String(v==null?'':v).replace(/\s+/g,' ').trim().slice(0,n||300);}
  function diag(kind,payload){
    try{
      const b=window.AIStudioWebSessionLab;
      if(b&&typeof b.onJsEvent==='function')b.onJsEvent(JSON.stringify({kind:'R21_'+kind,payload:payload||{}}));
    }catch(_){}
  }

  function overrideNavigatorEnvironment(){
    try{
      Object.defineProperty(navigator,'userAgent',{configurable:true,get:function(){return DESKTOP_UA;}});
      state.userAgentOverride=String(navigator.userAgent||'')===DESKTOP_UA;
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'userAgent',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}

    try{
      Object.defineProperty(navigator,'appVersion',{configurable:true,get:function(){return DESKTOP_UA.replace(/^Mozilla\//,'');}});
      state.appVersionOverride=true;
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'appVersion',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}

    try{
      Object.defineProperty(navigator,'maxTouchPoints',{configurable:true,get:function(){return 0;}});
      state.touchOverride=Number(navigator.maxTouchPoints||0)===0;
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'maxTouchPoints',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}

    try{
      const descriptor=Object.getOwnPropertyDescriptor(Navigator.prototype,'platform');
      if(!descriptor||descriptor.configurable!==false){
        Object.defineProperty(navigator,'platform',{configurable:true,get:function(){return 'Linux x86_64';}});
        state.platformOverride=true;
      }
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'platform',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}

    try{
      const original=navigator.userAgentData;
      if(original){
        const brands=Array.isArray(original.brands)?original.brands:[];
        const desktopData={
          brands:brands.map(function(x){return {brand:String(x&&x.brand||''),version:String(x&&x.brand==='Chromium'?'138':x&&x.version||'')};}),
          mobile:false,
          platform:'Linux',
          getHighEntropyValues:function(hints){
            const base=typeof original.getHighEntropyValues==='function'
              ? Promise.resolve(original.getHighEntropyValues.call(original,hints||[]))
              : Promise.resolve({});
            return base.then(function(v){
              const out=Object.assign({},v||{});
              out.mobile=false;out.platform='Linux';out.platformVersion=out.platformVersion||'6.0.0';out.fullVersionList=[{brand:'Chromium',version:'138.0.7204.179'}];
              return out;
            });
          },
          toJSON:function(){return {brands:this.brands,mobile:false,platform:'Linux'};}
        };
        Object.defineProperty(navigator,'userAgentData',{configurable:true,get:function(){return desktopData;}});
        state.userAgentDataOverride=true;
      }
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'userAgentData',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}
  }

  function overrideDesktopScreenAndPointer(){
    try{
      const values={width:DESKTOP_SCREEN_WIDTH,height:DESKTOP_SCREEN_HEIGHT,availWidth:DESKTOP_SCREEN_WIDTH,availHeight:DESKTOP_SCREEN_HEIGHT};
      Object.keys(values).forEach(function(k){
        try{Object.defineProperty(screen,k,{configurable:true,get:function(){return values[k];}});}catch(_){}
      });
      state.screenOverride=Number(screen.width||0)===DESKTOP_SCREEN_WIDTH&&Number(screen.height||0)===DESKTOP_SCREEN_HEIGHT;
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'screen',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}

    try{
      const nativeMatch=window.matchMedia&&window.matchMedia.bind(window);
      if(nativeMatch&&!nativeMatch.__aisR21Desktop){
        const wrapped=function(query){
          const q=String(query||'').toLowerCase();
          const m=nativeMatch(query);
          let forced=null;
          if(/\((?:any-)?pointer:\s*coarse\)/.test(q))forced=false;
          else if(/\((?:any-)?pointer:\s*fine\)/.test(q))forced=true;
          else if(/\((?:any-)?hover:\s*hover\)/.test(q))forced=true;
          else if(/\((?:any-)?hover:\s*none\)/.test(q))forced=false;
          if(forced===null)return m;
          try{Object.defineProperty(m,'matches',{configurable:true,get:function(){return forced;}});return m;}catch(_){}
          return {
            media:String(m&&m.media||query),matches:forced,onchange:null,
            addListener:function(cb){return m&&m.addListener?m.addListener(cb):undefined;},
            removeListener:function(cb){return m&&m.removeListener?m.removeListener(cb):undefined;},
            addEventListener:function(){return m&&m.addEventListener?m.addEventListener.apply(m,arguments):undefined;},
            removeEventListener:function(){return m&&m.removeEventListener?m.removeEventListener.apply(m,arguments):undefined;},
            dispatchEvent:function(){return m&&m.dispatchEvent?m.dispatchEvent.apply(m,arguments):false;}
          };
        };
        wrapped.__aisR21Desktop=true;window.matchMedia=wrapped;
      }
      state.pointerOverride=!!window.matchMedia&&!window.matchMedia('(pointer: coarse)').matches&&window.matchMedia('(pointer: fine)').matches&&window.matchMedia('(hover: hover)').matches;
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'matchMedia',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}
  }

  function preserveDesktopViewport(){
    state.viewportOverride=false;
    state.viewportMutationCount=0;
    state.viewportPolicy='native-wide-viewport';
    diag('VIEWPORT_PRESERVED',{
      policy:state.viewportPolicy,
      innerWidth:Number(window.innerWidth||0),
      documentClientWidth:Number(document.documentElement&&document.documentElement.clientWidth||0),
      note:'do-not-inject-mobile-meta-viewport'
    });
  }

  function patchSupportedConstraints(){
    try{
      const md=navigator.mediaDevices;
      if(!md||typeof md.getSupportedConstraints!=='function'||md.getSupportedConstraints.__aisR21DesktopShare)return;
      const nativeGetSupported=md.getSupportedConstraints.bind(md);
      const wrapped=function(){
        let base={};
        try{base=nativeGetSupported()||{};}catch(_){}
        return Object.assign({},base,{displaySurface:true,logicalSurface:true,cursor:true});
      };
      wrapped.__aisR21DesktopShare=true;
      try{md.getSupportedConstraints=wrapped;}catch(_){
        Object.defineProperty(md,'getSupportedConstraints',{configurable:true,writable:true,value:wrapped});
      }
      state.supportedConstraintsOverride=true;
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'getSupportedConstraints',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}
  }

  function environment(label){
    state.environmentReports++;
    let supported={};
    try{supported=navigator.mediaDevices&&typeof navigator.mediaDevices.getSupportedConstraints==='function'?navigator.mediaDevices.getSupportedConstraints():{};}catch(_){}
    const payload={
      report:state.environmentReports,
      label:String(label||''),
      href:safe(location.href,360),
      userAgent:safe(navigator.userAgent,420),
      platform:safe(navigator.platform,120),
      userAgentDataMobile:(function(){try{return navigator.userAgentData?navigator.userAgentData.mobile:null;}catch(_){return null;}})(),
      userAgentDataPlatform:(function(){try{return navigator.userAgentData?safe(navigator.userAgentData.platform,120):'';}catch(_){return '';}})(),
      innerWidth:Number(window.innerWidth||0),
      innerHeight:Number(window.innerHeight||0),
      documentClientWidth:Number(document.documentElement&&document.documentElement.clientWidth||0),
      visualViewportWidth:Number(window.visualViewport&&window.visualViewport.width||0),
      viewportMeta:(function(){try{const m=document.querySelector('meta[name="viewport"]');return safe(m&&m.getAttribute('content')||'',300);}catch(_){return '';}})(),
      screenWidth:Number(screen&&screen.width||0),
      screenHeight:Number(screen&&screen.height||0),
      devicePixelRatio:Number(window.devicePixelRatio||0),
      maxTouchPoints:Number(navigator.maxTouchPoints||0),
      coarsePointer:(function(){try{return !!window.matchMedia&&window.matchMedia('(pointer: coarse)').matches;}catch(_){return null;}})(),
      hoverCapable:(function(){try{return !!window.matchMedia&&window.matchMedia('(hover: hover)').matches;}catch(_){return null;}})(),
      mediaDevicesPresent:!!navigator.mediaDevices,
      getUserMedia:!!(navigator.mediaDevices&&typeof navigator.mediaDevices.getUserMedia==='function'),
      getDisplayMedia:!!(navigator.mediaDevices&&typeof navigator.mediaDevices.getDisplayMedia==='function'),
      supportedDisplaySurface:!!supported.displaySurface,
      supportedLogicalSurface:!!supported.logicalSurface,
      supportedCursor:!!supported.cursor,
      platformOverride:state.platformOverride,
      userAgentOverride:state.userAgentOverride,
      appVersionOverride:state.appVersionOverride,
      userAgentDataOverride:state.userAgentDataOverride,
      touchOverride:state.touchOverride,
      screenOverride:state.screenOverride,
      pointerOverride:state.pointerOverride,
      viewportPolicy:state.viewportPolicy,
      viewportOverride:state.viewportOverride,
      viewportMutationCount:state.viewportMutationCount,
      supportedConstraintsOverride:state.supportedConstraintsOverride
    };
    diag('ENVIRONMENT',payload);
    return payload;
  }

  function describe(){
    return {
      ok:true,version:VERSION,installedAt:state.installedAt,
      platformOverride:state.platformOverride,userAgentOverride:state.userAgentOverride,appVersionOverride:state.appVersionOverride,
      userAgentDataOverride:state.userAgentDataOverride,touchOverride:state.touchOverride,screenOverride:state.screenOverride,pointerOverride:state.pointerOverride,
      viewportPolicy:state.viewportPolicy,viewportOverride:state.viewportOverride,viewportMutationCount:state.viewportMutationCount,supportedConstraintsOverride:state.supportedConstraintsOverride,
      mediaDevicesPresent:state.mediaDevicesPresent,getDisplayMediaAtInstall:state.getDisplayMediaAtInstall,
      environmentReports:state.environmentReports
    };
  }

  overrideNavigatorEnvironment();
  overrideDesktopScreenAndPointer();
  preserveDesktopViewport();
  patchSupportedConstraints();
  window.__AIS_DESKTOP_SHARE_EXPERIMENT__={enabled:true,version:VERSION,mode:'desktop-share-screen'};
  window.__AIS_R21_DESKTOP_SHARE__={version:VERSION,describe:describe,environment:environment};
  environment('document-start');
  try{window.addEventListener('DOMContentLoaded',function(){environment('dom-content-loaded');},{once:true});}catch(_){}
  try{window.addEventListener('load',function(){environment('window-load');},{once:true});}catch(_){}
  diag('ENGINE_INSTALLED',{version:VERSION,mode:'desktop-share-screen',desktopEnvironment:true,desktopCssWidth:DESKTOP_CSS_WIDTH,desktopScreen:[DESKTOP_SCREEN_WIDTH,DESKTOP_SCREEN_HEIGHT],viewportPolicy:state.viewportPolicy});
})();
    """.trimIndent()
}
