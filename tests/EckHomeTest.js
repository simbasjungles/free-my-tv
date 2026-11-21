'use strict';
// Optional DOM integration suite: requires jsdom 26.1 (may be installed outside the repository via NODE_PATH).
var fs = require('fs'), path = require('path'), assert = require('assert'), JSDOM = require('jsdom').JSDOM;
function setup(saved, hasKey, startTime) {
  var dom = new JSDOM('<body><div id="stage"></div></body>', {url: 'http://localhost/', runScripts: 'outside-only', pretendToBeVisual: true});
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
assert.strictEqual(t.requests.length, 3, 'initial All and showcase share three HTTP requests');
batch(t, 0, movies(100, 30));
assert.strictEqual(w.document.querySelectorAll('.eck-app').length, 8);
assert.strictEqual(w.document.querySelectorAll('.eck-top button').length, 2);
assert(!w.document.querySelector('.eck-actions')); assert(!w.document.querySelector('.eck-hero-note'));
assert.strictEqual(w.document.querySelector('.eck-genre.chosen').textContent, 'Tümü');
var lower = state(t).catalog;
t.advance(200);
var oldHeroImage = t.images.filter(function (img) { return /film100.jpg$/.test(img.src || ''); })[0];
w.ECKHome.move(1, 0); assert.notStrictEqual(state(t).showcase.id, 100);
assert.deepStrictEqual(state(t).catalog, lower, 'upper manual movement cannot change lower state');
t.advance(200);
var newHeroImage = t.images.filter(function (img) { return (img.src || '').indexOf('/film' + state(t).showcase.id + '.jpg') >= 0; })[0];
newHeroImage.onload(); oldHeroImage.onload();
assert(w.document.querySelector('.eck-hero-photo').style.backgroundImage.indexOf('/film' + state(t).showcase.id + '.jpg') >= 0, 'late upper image cannot replace the new one');
var upper = state(t).showcase, title = w.document.querySelector('h1').textContent;
t.advance(31000); assert.deepStrictEqual(state(t).showcase, upper, 'rotation pauses while upper focused');
down(t, 4); assert.strictEqual(state(t).row, 1);
w.ECKHome.move(1, 0); w.ECKHome.move(1, 0);
assert.deepStrictEqual(state(t).showcase, upper); assert.strictEqual(w.document.querySelector('h1').textContent, title);
lower = state(t).catalog;
var firstCard = w.document.querySelector('.eck-film');
t.advance(31000); assert.notDeepStrictEqual(state(t).showcase, upper);
assert.deepStrictEqual(state(t).catalog, lower, 'automatic rotation leaves catalog selection fixed');
assert.strictEqual(w.document.querySelector('.eck-film'), firstCard, 'rotation does not rebuild or scroll the catalog DOM');
// Lower and upper trailers each dispatch their own current movie and resume their own positions.
w.ECKHome.activate(); var n = t.requests.length;
assert(t.requests[n - 1].url.indexOf('/movie/' + lower.id + '/videos') >= 0);
t.requests[n - 1].reply({results: [{site: 'YouTube', type: 'Trailer', iso_639_1: 'tr', key: 'abcdefghijk'}]});
assert.strictEqual(t.videos[0].video, 'abcdefghijk');
var beforeReturn = state(t); w.ECKHome.resume(); assert.deepStrictEqual(state(t), beforeReturn);
for (var i = 0; i < 4; i++) w.ECKHome.move(0, -1);
assert.strictEqual(state(t).row, 5); upper = state(t).showcase;
w.ECKHome.activate(); n = t.requests.length; assert(t.requests[n - 1].url.indexOf('/movie/' + upper.id + '/videos') >= 0);
t.requests[n - 1].reply({results: []}); t.requests[t.requests.length - 1].reply({results: []});
assert(t.videos[1].query.indexOf('Film ' + upper.id) >= 0);
beforeReturn = state(t); w.ECKHome.resume(); assert.deepStrictEqual(state(t), beforeReturn);
Object.defineProperty(w.document, 'hidden', {configurable: true, value: true});
w.document.dispatchEvent(new w.Event('visibilitychange')); t.advance(31000); assert.deepStrictEqual(state(t), beforeReturn, 'hidden app does not rotate');
Object.defineProperty(w.document, 'hidden', {configurable: true, value: false});
w.document.dispatchEvent(new w.Event('visibilitychange')); assert.deepStrictEqual(state(t), beforeReturn);
// Only catalog changes on a real TMDB genre selection; focus resets without moving away from the genre bar.
down(t, 3); assert.strictEqual(state(t).row, 6);
w.ECKHome.move(1, 0); n = t.requests.length; w.ECKHome.activate();
assert.strictEqual(state(t).catalog.genre, 28); assert.strictEqual(state(t).catalog.index, 0);
assert(t.requests[n].url.indexOf('with_genres=28') >= 0);
assert.deepStrictEqual(state(t).showcase, upper);
batch(t, n, movies(500, 30)); down(t, 1); w.ECKHome.move(1, 0);
assert.strictEqual(state(t).catalog.genre, 28);
assert.strictEqual(w.document.querySelector('.eck-genre.chosen').textContent, 'Aksiyon');
lower = state(t).catalog; upper = state(t).showcase;
// A restart restores the genre, both film identities and lower focus/window from cached metadata without network.
var restarted = setup(t.saved(), true, t.now()); assert.strictEqual(restarted.requests.length, 0);
assert.deepStrictEqual(state(restarted).catalog, lower); assert.deepStrictEqual(state(restarted).showcase, upper);
// Horizontal overflow and additional real genres remain reachable with the remote.
w.ECKHome.move(0, -1); for (i = 0; i < 8; i++) w.ECKHome.move(1, 0);
assert.strictEqual(state(t).catalog.genreFocus, 8); w.ECKHome.activate(); assert(w.document.querySelector('.eck-modal'));
var rows = w.document.querySelectorAll('.eck-menu-row');
var western = Array.from(rows).filter(function (b) { return b.textContent === 'Western'; })[0];
n = t.requests.length; western.click(); assert.strictEqual(state(t).catalog.genre, 37); assert.strictEqual(state(t).row, 6);
batch(t, n, movies(700, 2)); assert.deepStrictEqual(state(t).showcase, upper);
w.ECKHome.settings(); t.advance(31000); assert.deepStrictEqual(state(t).showcase, upper, 'modal pauses rotation');
w.ECKHome.back(); w.ECKHome.showSaver(); t.advance(31000); assert.deepStrictEqual(state(t).showcase, upper, 'ambient pauses rotation');
w.document.dispatchEvent(new w.KeyboardEvent('keydown', {keyCode: 13, bubbles: true})); assert(!w.document.querySelector('.eck-saver'));
// Empty-key setup remains actionable independently of an empty lower list.
var empty = setup(null, false); empty.w.document.querySelector('.eck-hero').click(); assert(empty.w.document.querySelector('input[type=password]'));
console.log('EckHomeTest: six independence scenarios, request reuse, both trailer/resume paths, restart, genre overflow, modal/ambient pause and empty-key setup passed.');
