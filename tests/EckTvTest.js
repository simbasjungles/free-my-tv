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
sandbox.TV.youtube('youtube', 'abcdefghijk', 'Film 2024 resmi fragman Türkçe', function (ok) { result = ok; });
assert(result); assert(controls[0].uri.indexOf('watch?v=abcdefghijk') >= 0);
assert(controls[1].uri.indexOf('results?search_query=') >= 0, 'request errors must fall back to search');
assert.strictEqual(sandbox.TV.canOpenSettings, false, '2018 Samsung settings launch stays disabled');
console.log('EckTvTest: satellite preservation and YouTube request fallback passed (mock APIs; device test still required).');
