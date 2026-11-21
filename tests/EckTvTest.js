'use strict';
var assert = require('assert'), fs = require('fs'), vm = require('vm'), path = require('path');
var tuned = 0, opened = [], controls = [], source = 0;
var tizen = {
  application: {getCurrentApplication: function () { return {appInfo: {id: 'FreeMyTVio.FreeMyTV'}}; },
    launch: function (id, ok) { opened.push(id); ok(); }, launchAppControl: function (c, id, ok, fail) { controls.push(c); if (controls.length === 1) fail(); else ok(); }},
  systeminfo: {getPropertyValue: function (name, ok) { ok({connected: [{type: 'TV', number: 1}]}); }},
  tvwindow: {setSource: function (s, ok) { source++; ok(); }},
  tvchannel: {getChannelList: function () { tuned++; }, tune: function () { tuned++; }},
  ApplicationControl: function (op, uri) { this.operation = op; this.uri = uri; }
};
var sandbox = {window: {tizen: tizen, CONFIG: {eckHome: true}}, document: {documentElement: {className: ''}},
  navigator: {userAgent: 'Tizen 4.0 Chrome/56'}, setTimeout: function () {}, clearTimeout: function () {},
  localStorage: {getItem: function () { return null; }}};
vm.createContext(sandbox); vm.runInContext(fs.readFileSync(path.join(__dirname, '../web/tv.js'), 'utf8'), sandbox);
var result;
sandbox.TV.launch('source:TV:1', null, function (ok) { result = ok; });
assert.strictEqual(result, true); assert.strictEqual(source, 1); assert.strictEqual(tuned, 0, 'must preserve satellite tuning');
assert.strictEqual(opened[0], 'org.tizen.tv-viewer');
sandbox.TV.youtube('9Ur5IzDKqV.TizenYouTube', 'abcdefghijk', 'Film 2024 resmi fragman Türkçe', function (ok) { result = ok; });
assert.strictEqual(result,false); assert(controls[0].uri.indexOf('watch?v=abcdefghijk') >= 0);
assert.strictEqual(controls.length,1,'no search/home fallback');
sandbox.TV.youtube('9Ur5IzDKqV.TizenYouTube', 'abcdefghijk', '', function (ok) { result = ok; });
assert.strictEqual(result,false,'accepted control is not verified playback');
sandbox.TV.youtube('unofficial.youtube', 'abcdefghijk', '', function (ok) { result = ok; });assert.strictEqual(result,false);
var requests=[];
sandbox.XMLHttpRequest=function(){requests.push(this);this.open=function(method,url){this.url=url;};this.setRequestHeader=this.send=function(){};this.reply=function(status){this.status=status;this.readyState=4;this.onreadystatechange();};};
var before=opened.length;
sandbox.TV.launch('RN1MCdNq8t.Netflix',null,function(ok){result=ok;});
assert(requests[0].url.indexOf('/api/v2/applications/11101200001')>=0);
requests[0].reply(404);assert(requests[1].url.indexOf('/ws/apps/Netflix')>=0);requests[1].reply(500);
assert.strictEqual(opened.length,before,'Tizen 4 Netflix never falls back to the known black-screen launch');
assert.strictEqual(result,false);
assert.strictEqual(sandbox.TV.canOpenSettings, false, '2018 Samsung settings launch stays disabled');
console.log('EckTvTest: satellite preservation, exact official YouTube only/no false playback success, Netflix Hub/DIAL without black-screen fallback passed (mock APIs).');
