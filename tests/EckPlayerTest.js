'use strict';
var assert=require('assert'),fs=require('fs'),path=require('path'),JSDOM=require('jsdom').JSDOM;
function testPlayer(){
 var w=new JSDOM('<main id="root"></main>',{url:'http://localhost/',runScripts:'outside-only'}).window;
 var intervals=[],timeout,events,target,video='abcdefghijk',time=0,mode=1,closed=0,errors=[];
 w.setInterval=function(fn){intervals.push(fn);return intervals.length;};w.clearInterval=function(){};
 w.setTimeout=function(fn){timeout=fn;return 1;};w.clearTimeout=function(){timeout=null;};
 w.YT={Player:function(id,opts){events=opts.events;target={destroy:function(){},playVideo:function(){},getIframe:function(){return w.document.createElement('iframe');},getPlayerState:function(){return mode;},getCurrentTime:function(){return time;},getVideoData:function(){return {video_id:video};}};return target;}};
 w.eval(fs.readFileSync(path.join(__dirname,'../web/eck-player.js'),'utf8'));
 function open(){w.ECKPlayer.open(w.document.querySelector('main'),'abcdefghijk',function(){closed++;},function(e){errors.push(e);});events.onReady({target:target});}
 open();assert.strictEqual(w.ECKPlayer.state().confirmed,false,'ready is not playing');
 events.onStateChange({data:1});assert.strictEqual(w.ECKPlayer.state().confirmed,false);
 intervals[0]();time=1;video='wrongwrong1';intervals[0]();assert.strictEqual(w.ECKPlayer.state().confirmed,false,'wrong video cannot confirm');
 video='abcdefghijk';time=2;intervals[0]();assert.strictEqual(w.ECKPlayer.state().confirmed,true,'matching id and advancing playing time confirm');
 w.ECKPlayer.close();assert.strictEqual(closed,1);assert(!w.ECKPlayer.active());
 open();events.onError({data:153});assert(!w.ECKPlayer.active());assert.strictEqual(w.ECKPlayer.state().confirmed,false);assert(errors[0].indexOf('153')>=0);
 open();timeout();assert(!w.ECKPlayer.active());assert(errors[1].indexOf('timeout')>=0);
 console.log('EckPlayerTest: ready/accepted are not success, wrong-video rejection, advancing playback, Back cleanup, 153 and timeout passed (mock API).');
}
testPlayer();
function testBridge(){
 var w=new JSDOM('<main></main>',{url:'file:///opt/eck/index.html',runScripts:'outside-only'}).window,errors=[],closed=0;
 w.eval(fs.readFileSync(path.join(__dirname,'../web/eck-player.js'),'utf8'));
 w.ECKPlayer.open(w.document.querySelector('main'),'abcdefghijk',function(){closed++;},function(e){errors.push(e);});
 var frame=w.document.querySelector('iframe');
 function message(origin,source,video,phase){w.dispatchEvent(new w.MessageEvent('message',{origin:origin,source:source,data:{type:'eck-player',video:video,phase:phase,confirmed:true}}));}
 message('https://other.example',frame.contentWindow,'abcdefghijk','playing');assert(!w.ECKPlayer.state().confirmed);
 message('https://celilkamis-arch.github.io',w,'abcdefghijk','playing');assert(!w.ECKPlayer.state().confirmed);
 message('https://celilkamis-arch.github.io',frame.contentWindow,'wrongwrong1','playing');assert(!w.ECKPlayer.state().confirmed);
 message('https://celilkamis-arch.github.io',frame.contentWindow,'abcdefghijk','playing');assert(w.ECKPlayer.state().confirmed);
 message('https://celilkamis-arch.github.io',frame.contentWindow,'abcdefghijk','close');assert(!w.ECKPlayer.active());assert.strictEqual(closed,1);assert.strictEqual(errors.length,0);
 w.close();console.log('EckPlayerTest: HTTPS bridge validates source, origin and video; trusted playback and Back cleanup passed.');
}
testBridge();
