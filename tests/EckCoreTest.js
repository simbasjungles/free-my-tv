'use strict';
var assert = require('assert'), fs = require('fs'), vm = require('vm'), path = require('path');
function core() {
  var memory = {}, requests = [], images = [], callbacks = [], timers = [];
  var sandbox = {
    localStorage: {getItem: function (k) { return memory[k] || null; }, setItem: function (k, v) { memory[k] = v; }},
    Prefs: {tmdbKey: function () { return 'test-only-placeholder'; }},
    setTimeout: function (fn) { timers.push(fn); return timers.length; }, clearTimeout: function () {},
    Image: function () { images.push(this); },
    XMLHttpRequest: function () {
      requests.push(this); this.open = function (method, url) { this.url = url; };
      this.setRequestHeader = function () {}; this.send = function () {};
      this.reply = function (data, status) { this.status = status || 200; this.readyState = 4; this.responseText = JSON.stringify(data); this.onreadystatechange(); };
    }
  };
  vm.createContext(sandbox); vm.runInContext(fs.readFileSync(path.join(__dirname, '../web/eck-core.js'), 'utf8'), sandbox);
  return {eck: sandbox.ECK, sandbox: sandbox, memory: memory, requests: requests, images: images, callbacks: callbacks, timers: timers};
}
function movie(id) { return {id: id, title: 'Film ' + id, backdrop_path: '/photo.jpg', release_date: '2024-01-01'}; }
var c = core(), e = c.eck;
// Region-specific lists must never depend on the installed streaming services.
e.refresh(true); assert(c.requests[0].url.indexOf('/movie/popular?language=tr-TR&region=TR') >= 0);
e.setMode('genres'); assert(c.requests[1].url.indexOf('with_genres=') >= 0);
c.requests[1].reply({results: [movie(2)]}); c.requests[0].reply({results: [movie(1)]});
assert.strictEqual(e.list()[0].id, 2, 'a late popular response must not overwrite the genre list');
e.setMode('popular'); c.requests[2].reply({results: [movie(3), movie(4)]});
e.hide(3); assert.strictEqual(e.list().length, 1); e.resetHidden(); assert.strictEqual(e.list().length, 2);
e.toggleFavourite(e.list()[0]); assert(e.isFavourite(3)); e.toggleFavourite(e.list()[0]); assert(!e.isFavourite(3));
for (var i = 1; i < 100; i++) e.toggleFavourite({id: i, title: 'Film'});
assert.strictEqual(e.favourites().length, 80, 'favourites must be bounded');
for (i = 0; i < 230; i++) e.hide(i); assert.strictEqual(e.read('hidden', []).length, 200);
assert.strictEqual(Object.keys(c.memory).filter(function (k) { return k.indexOf('movies.') >= 0; }).length, 2, 'only two list caches');
var count = c.requests.length; e.refresh(false); assert.strictEqual(c.requests.length, count, 'fresh cache avoids network');
e.refresh(true); c.requests[c.requests.length - 1].reply({}, 503); assert(e.error().indexOf('son kaydedilen') >= 0);
// Turkish trailer, official tie-break, invalid ids/teasers filtered out.
var v = [
  {site: 'YouTube', type: 'Trailer', key: 'abcdefghijk', iso_639_1: 'en', official: true},
  {site: 'YouTube', type: 'Trailer', key: '12345678901', iso_639_1: 'tr', official: true},
  {site: 'YouTube', type: 'Trailer', key: 'invalid', iso_639_1: 'tr', official: true}
];
assert.strictEqual(e.bestTrailer(v), '12345678901');
assert.strictEqual(e.bestTrailer([{site: 'YouTube', type: 'Teaser', key: 'abcdefghijk'}]), '');
assert.strictEqual(e.searchQuery({title: 'Aynı İsim', year: '2024'}), '"Aynı İsim" 2024 resmi fragman Türkçe');
assert(e.searchQuery({title: 'Film\n"adı"', year: 'bad'}).indexOf('\n') < 0);
var found; e.trailer({id: 1, title: 'Film', year: '2024'}, function (id, query) { found = [id, query]; });
c.requests[c.requests.length - 1].reply({results: []});
c.requests[c.requests.length - 1].reply({results: [v[0]]}); assert.strictEqual(found[0], 'abcdefghijk');
e.trailer({id: 1, title: 'Film', year: '2024'}, function (id, query) { found = [id, query]; });
c.requests[c.requests.length - 1].reply({}, 503); c.requests[c.requests.length - 1].reply({}, 503);
assert.strictEqual(found[0], ''); assert(found[1].indexOf('2024') >= 0);
// A failed/evicted preload must settle its waiting callers and remain retryable.
var p = e.imagePool(2), calls = [];
p.load('a', function (ok) { calls.push(ok); }); p.load('a', function (ok) { calls.push(ok); });
c.images[0].onload(); assert.strictEqual(calls.join(','), 'true,true');
p.load('b', function () {}); p.load('c', function () {}); assert.strictEqual(p.size(), 2);
var q = e.imagePool(1), settled = false;
q.load('pending', function (ok) { settled = !ok; }); q.load('new', function () {}); assert(settled);
console.log('EckCoreTest: region, modes, stale responses, offline cache, preferences, trailers and bounded images passed.');
var changed = core(); changed.eck.toggleGenre(27);
assert.strictEqual(changed.eck.mode(), 'genres');
assert.strictEqual(changed.requests.length, 1, 'genre selection starts a live query immediately');
assert(changed.requests[0].url.indexOf('with_genres=28|35|27|878') >= 0 || changed.requests[0].url.indexOf('27') >= 0);
changed.requests[0].reply({results:[movie(55)]}); assert.strictEqual(changed.eck.list()[0].id,55);
changed.sandbox.Prefs.tmdbKey = function () { return ''; };
changed.eck.refresh(true); assert(changed.eck.error().indexOf('TMDB') >= 0);
var sceneList = [{url:'a'},{url:'b'},{url:'c'}];
assert.strictEqual(e.sceneStart(sceneList,'a',function(){return 0;}),1);
assert.strictEqual(e.sceneStart(sceneList,'c',function(){return 0.999;}),1);
assert.strictEqual(e.sceneStart([{url:'a'}],'a',function(){return 0;}),0);
assert.strictEqual(e.sceneStart([],'a',function(){return 0;}),0);
console.log('EckCoreTest: immediate genre discovery, missing key and non-repeating ambient start passed.');
