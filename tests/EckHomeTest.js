'use strict';
// Optional DOM integration suite: requires jsdom 26.1 (may be installed outside the repository via NODE_PATH).
var fs = require('fs'), path = require('path'), assert = require('assert'), JSDOM = require('jsdom').JSDOM;
function setup(saved, hasKey, startTime) {
  var dom = new JSDOM(fs.readFileSync(path.join(__dirname, '../web/index.html'), 'utf8'), {url: 'http://localhost/', runScripts: 'outside-only', pretendToBeVisual: true});
  var w = dom.window, requests = [], launches = [], videos = [], intervals = [], timers = {}, serial = 0, now = startTime || Date.now(), images = [];
  Object.keys(saved || {}).forEach(function (k) { w.localStorage.setItem(k, saved[k]); });
  w.Date.now = function () { return now; };
  w.setTimeout = function (fn, ms) { timers[++serial] = {fn: fn, at: now + ms}; return serial; };
  w.clearTimeout = function (id) { delete timers[id]; };
  w.setInterval = function (fn) { intervals.push(fn); return intervals.length; };
  w.Image = function () { images.push(this); };
  w.CONFIG = {eckHome: true}; w.I18N = {set: function () {}, setClock: function () {}};
  w.formatDate = function () { return 'Date'; };
  w.Prefs = {tmdbKey: function () { return hasKey === false ? '' : 'test-only-placeholder'; }, city: function () { return ''; }, theme: function () { return 'auto'; }};
  w.Data = {weather: null, onChange: function () {}, refresh: function () {}, isNight: function () { return true; }, image: function (p) { return 'https://image.tmdb.org/t/p/w342' + p; }};
  var titles = ['YouTube', 'Netflix', 'Disney+', 'Prime Video', 'HBO Max', 'Internet', 'Apple TV', 'Spotify', 'Extra app'];
  w.TV = {name: 'browser', keys: {back: 461}, apps: function (cb) { cb(titles.map(function (title, i) { return {id: 'app' + i, title: title}; })); },
    launch: function (id, p, cb) { launches.push(id); cb(true); }, youtube: function (id, video, query, cb) { videos.push({video: video, query: query}); cb(true); }, back: function () {}};
  w.XMLHttpRequest = function () {
    requests.push(this); this.open = function (m, url) { this.url = url; }; this.setRequestHeader = this.send = function () {};
    this.reply = function (body, status) { this.status = status || 200; this.readyState = 4; this.responseText = JSON.stringify(body); this.onreadystatechange(); };
  };
  w.eval(fs.readFileSync(path.join(__dirname, '../web/eck-core.js'), 'utf8'));
  w.ECK.request = function (url, headers, cb) {
    if (url === 'eck-scenes.json') cb(null, w.JSON.parse(JSON.stringify({scenes: [{url: 'https://images.unsplash.com/a'}, {url: 'https://images.unsplash.com/b'}]})));
    else cb(new Error('offline'));
  };
  w.eval(fs.readFileSync(path.join(__dirname, '../web/eck-home.js'), 'utf8'));
  return {w: w, requests: requests, videos: videos, images: images, launches: launches, now: function () { return now; },
    advance: function (ms) {
      now += ms; intervals.forEach(function (fn) { fn(); });
      Object.keys(timers).forEach(function (id) { var t = timers[id]; if (t && t.at <= now) { delete timers[id]; t.fn(); } });
    }, saved: function () { var result = {}; for (var i = 0; i < w.localStorage.length; i++) { var k = w.localStorage.key(i); result[k] = w.localStorage.getItem(k); } return result; }};
}
function movies(start, count) {
  return Array.from({length: count}, function (_, i) { return {id: start + i, title: 'Film ' + (start + i), release_date: '2025-01-01', backdrop_path: '/film' + (start + i) + '.jpg', genre_ids: [i % 2 ? 35 : 28]}; });
}
function batch(t, start, list, status) { for (var i = 0; i < 3; i++) t.requests[start + i].reply({results: list}, status); }
function state(t) { return JSON.parse(JSON.stringify(t.w.ECKHome.state())); }
function down(t, count) { for (var i = 0; i < count; i++) t.w.ECKHome.move(0, 1); }
var t = setup(), w = t.w;
assert.strictEqual(t.requests.length,3,'only the visible showcase fetches');
batch(t,0,movies(100,30));
assert.strictEqual(w.document.querySelectorAll('.eck-app').length,8);
assert.strictEqual(w.document.querySelectorAll('.eck-top button').length,3);
assert.strictEqual(w.document.querySelectorAll('.eck-rail,.eck-genres,.eck-heading,.eck-footer,.eck-footer-right').length,0);
assert(!w.document.querySelector('#eck-boot'),'boot yields immediately to the home screen');
assert.strictEqual(w.document.title,'ECK TV');
assert(!w.document.querySelector('.eck-hero').contains(w.document.querySelector('.eck-eyebrow')));
var upper=state(t).showcase;
t.advance(31000);assert.deepStrictEqual(state(t).showcase,upper,'focused showcase pauses');
w.ECKHome.move(1,0);assert.notStrictEqual(state(t).showcase.id,upper.id);
w.ECKHome.move(0,1);assert.strictEqual(state(t).row,3);
for(var i=0;i<7;i++)w.ECKHome.move(1,0);
w.ECKHome.activate();assert.strictEqual(t.launches[0],'app7','all eight apps reachable on one row');
upper=state(t).showcase;t.advance(31000);assert.notDeepStrictEqual(state(t).showcase,upper,'showcase rotates outside focus');
w.ECKHome.move(0,-1);assert.strictEqual(state(t).row,5);
upper=state(t).showcase;var n=t.requests.length;w.ECKHome.activate();
assert(t.requests[n].url.indexOf('/movie/'+upper.id+'/videos')>=0);
t.requests[n].reply({results:[{site:'YouTube',type:'Trailer',iso_639_1:'tr',key:'abcdefghijk'}]});
assert.strictEqual(t.videos[0].video,'abcdefghijk');
var before=state(t);w.ECKHome.resume();assert.deepStrictEqual(state(t),before);
var restarted=setup(t.saved(),true,t.now());assert.strictEqual(restarted.requests.length,0);
assert.deepStrictEqual(state(restarted).showcase,state(t).showcase);
w.document.querySelector('.eck-top button').click();assert.strictEqual(t.launches[1],'source:TV:1');
w.ECKHome.settings();upper=state(t).showcase;t.advance(31000);assert.deepStrictEqual(state(t).showcase,upper);
w.ECKHome.back();w.ECKHome.showSaver();t.advance(31000);assert.deepStrictEqual(state(t).showcase,upper);
w.document.dispatchEvent(new w.KeyboardEvent('keydown',{keyCode:13,bubbles:true}));assert(!w.document.querySelector('.eck-saver'));
var empty=setup(null,false);empty.w.document.querySelector('.eck-hero').click();assert(empty.w.document.querySelector('input[type=password]'));
var icon=fs.readFileSync(path.join(__dirname,'../tizen/icon.png'));
assert.strictEqual(icon.readUInt32BE(16),117);assert.strictEqual(icon.readUInt32BE(20),117);
var manifest=fs.readFileSync(path.join(__dirname,'../tizen/config.xml'),'utf8');
assert(manifest.indexOf('id="ECKTVHome1.ECKTV" package="ECKTVHome1"')>=0);
console.log('EckHomeTest: single showcase/app row, removed catalog/footer, ECK boot/title/icon, remote navigation, rotation/pause, trailer/resume, live TV and preferences passed.');
