package com.oai.geminilivetranslate.ui

/**
 * Experimental document-start shim for exercising AI Studio's own desktop Share Screen path
 * from Android WebView. This branch is intentionally isolated from main.
 */
object AiStudioWebSessionR21DesktopShareScreenExperiment {
    const val VERSION = "2026-09-18-r21.1-desktop-share-screen-environment"

    val DOCUMENT_START: String = """
(function(){
  'use strict';
  if(window.__AIS_R21_DESKTOP_SHARE__&&window.__AIS_R21_DESKTOP_SHARE__.version)return;

  const VERSION='2026-09-18-r21.1-desktop-share-screen-environment';
  const state={
    installedAt:Date.now(),
    platformOverride:false,
    userAgentDataOverride:false,
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
          brands:brands,
          mobile:false,
          platform:'Linux',
          getHighEntropyValues:function(hints){
            const base=typeof original.getHighEntropyValues==='function'
              ? Promise.resolve(original.getHighEntropyValues.call(original,hints||[]))
              : Promise.resolve({});
            return base.then(function(v){
              const out=Object.assign({},v||{});
              out.mobile=false;out.platform='Linux';out.platformVersion=out.platformVersion||'6.0.0';
              return out;
            });
          },
          toJSON:function(){return {brands:brands,mobile:false,platform:'Linux'};}
        };
        Object.defineProperty(navigator,'userAgentData',{configurable:true,get:function(){return desktopData;}});
        state.userAgentDataOverride=true;
      }
    }catch(e){diag('ENV_OVERRIDE_ERROR',{target:'userAgentData',name:String(e&&e.name||'Error'),message:safe(e&&e.message||'',240)});}
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
      screenWidth:Number(screen&&screen.width||0),
      screenHeight:Number(screen&&screen.height||0),
      devicePixelRatio:Number(window.devicePixelRatio||0),
      mediaDevicesPresent:!!navigator.mediaDevices,
      getUserMedia:!!(navigator.mediaDevices&&typeof navigator.mediaDevices.getUserMedia==='function'),
      getDisplayMedia:!!(navigator.mediaDevices&&typeof navigator.mediaDevices.getDisplayMedia==='function'),
      supportedDisplaySurface:!!supported.displaySurface,
      supportedLogicalSurface:!!supported.logicalSurface,
      supportedCursor:!!supported.cursor,
      platformOverride:state.platformOverride,
      userAgentDataOverride:state.userAgentDataOverride,
      supportedConstraintsOverride:state.supportedConstraintsOverride
    };
    diag('ENVIRONMENT',payload);
    return payload;
  }

  function describe(){
    return {
      ok:true,version:VERSION,installedAt:state.installedAt,
      platformOverride:state.platformOverride,userAgentDataOverride:state.userAgentDataOverride,
      supportedConstraintsOverride:state.supportedConstraintsOverride,
      mediaDevicesPresent:state.mediaDevicesPresent,getDisplayMediaAtInstall:state.getDisplayMediaAtInstall,
      environmentReports:state.environmentReports
    };
  }

  overrideNavigatorEnvironment();
  patchSupportedConstraints();
  window.__AIS_DESKTOP_SHARE_EXPERIMENT__={enabled:true,version:VERSION,mode:'desktop-share-screen'};
  window.__AIS_R21_DESKTOP_SHARE__={version:VERSION,describe:describe,environment:environment};
  environment('document-start');
  try{window.addEventListener('DOMContentLoaded',function(){environment('dom-content-loaded');},{once:true});}catch(_){}
  try{window.addEventListener('load',function(){environment('window-load');},{once:true});}catch(_){}
  diag('ENGINE_INSTALLED',{version:VERSION,mode:'desktop-share-screen',desktopEnvironment:true});
})();
    """.trimIndent()
}
