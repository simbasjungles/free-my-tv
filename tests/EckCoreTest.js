'use strict';
var assert = require('assert'), fs = require('fs'), vm = require('vm'), path = require('path');
function core(saved) {
  var memory = saved || {}, requests = [], images = [], callbacks = [], timers = [];
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
function replyBatch(c, start, list, status) { for (var j = 0; j < 3; j++) c.requests[start + j].reply({results:list}, status); }
e.refresh(true); assert(c.requests[0].url.indexOf('/discover/movie?language=tr-TR&region=TR') >= 0);
assert(c.requests[0].url.indexOf('primary_release_date.gte=') >= 0);
assert(c.requests[1].url.indexOf('page=2') >= 0);
assert(c.requests[2].url.indexOf('primary_release_date.gte=') < 0, 'backfill can include older popular films');
assert.strictEqual(e.genre(),0, 'new catalog defaults to All');
e.setGenre(28); assert(c.requests[3].url.indexOf('with_genres=28') >= 0);
replyBatch(c,3,[movie(2)]); replyBatch(c,0,[movie(1)]);
assert.strictEqual(e.list()[0].id, 2, 'a late popular response must not overwrite the genre list');
e.setGenre(0); e.refresh(true); replyBatch(c,6,[movie(3),movie(4)]);
e.hide(3); assert.strictEqual(e.list().length, 1); e.resetHidden(); assert.strictEqual(e.list().length, 2);
e.toggleFavourite(e.list()[0]); assert(e.isFavourite(3)); e.toggleFavourite(e.list()[0]); assert(!e.isFavourite(3));
for (var i = 1; i < 100; i++) e.toggleFavourite({id: i, title: 'Film'});
assert.strictEqual(e.favourites().length, 80, 'favourites must be bounded');
for (i = 0; i < 230; i++) e.hide(i); assert.strictEqual(e.read('hidden', []).length, 200);
assert.strictEqual(Object.keys(c.memory).filter(function (k) { return k.indexOf('movies.') >= 0; }).length, 1, 'one catalog slot regardless of genre changes');
var count = c.requests.length; e.refresh(false); assert.strictEqual(c.requests.length, count, 'fresh cache avoids network');
count = c.requests.length; e.refresh(true); replyBatch(c,count,[],503); assert(e.error().indexOf('son kaydedilen') >= 0);
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
var changed = core(); changed.eck.setGenre(27);
assert.strictEqual(changed.eck.genre(), 27);
assert.strictEqual(changed.requests.length, 3, 'genre selection starts recent pages and backfill immediately');
assert(changed.requests[0].url.indexOf('with_genres=27') >= 0);
replyBatch(changed,0,[movie(55)]); assert.strictEqual(changed.eck.list()[0].id,55);
changed.sandbox.Prefs.tmdbKey = function () { return ''; };
changed.eck.refresh(true); assert(changed.eck.error().indexOf('TMDB') >= 0);
var sceneList = [{url:'a'},{url:'b'},{url:'c'}];
assert.strictEqual(e.sceneStart(sceneList,'a',function(){return 0;}),1);
assert.strictEqual(e.sceneStart(sceneList,'c',function(){return 0.999;}),1);
assert.strictEqual(e.sceneStart([{url:'a'}],'a',function(){return 0;}),0);
assert.strictEqual(e.sceneStart([],'a',function(){return 0;}),0);
console.log('EckCoreTest: immediate genre discovery, missing key and non-repeating ambient start passed.');
var shelf = e.launcherApps([
  {id:'music',title:'Amazon Music'}, {id:'kids',title:'YouTube Kids'},
  {id:'b',title:'Web Browser'}, {id:'s',title:'Spotify'}, {id:'n',title:'Netflix'},
  {id:'yt',title:'YouTube'}, {id:'p',title:'Prime Video'}, {id:'d',title:'Disney+'},
  {id:'NvZ26VG9Yw.HBOGOLAG',title:'Max'}, {id:'a',title:'Apple TV'}, {id:'plex',title:'Plex'}
]);
assert.strictEqual(shelf.map(function(a){return a.key;}).join(','),'youtube,netflix,disney,prime,hbo,internet,apple,spotify');
assert.strictEqual(shelf[0].id,'yt');assert.strictEqual(shelf[4].id,'NvZ26VG9Yw.HBOGOLAG');assert.strictEqual(shelf[5].id,'b');
assert.strictEqual(shelf.length,8);assert(shelf.every(function(a){return a.available;}));
var missing=e.launcherApps([{id:'k',title:'YouTube Kids'},{id:'m',title:'Amazon Music'},{id:'c',title:'Cinemax'}]);
assert(missing.every(function(a){return a.id===null && !a.available;}));
console.log('EckCoreTest: fixed eight-app shelf, legacy ids, exclusions and missing-app handling passed.');
var fresh = core(), batch = [];
for (i=0;i<45;i++) { var item=movie(1000+i); item.release_date='2025-01-'+('0'+(i%28+1)).slice(-2); batch.push(item); }
batch.push({id:9,title:'Future',backdrop_path:'/f.jpg',release_date:'2099-01-01'});
fresh.eck.refresh(true); fresh.requests[0].reply({results:batch.slice(0,20)}); fresh.requests[1].reply({results:batch.slice(20)}); fresh.requests[2].reply({results:batch});
assert.strictEqual(fresh.eck.list().length,30);
assert.strictEqual(new Set(fresh.eck.list().map(function(m){return m.id;})).size,30);
assert(!fresh.eck.list().some(function(m){return m.id===9;}));
for(i=1;i<30;i++) assert(fresh.eck.list()[i-1].released>=fresh.eck.list()[i].released,'newer releases come first');
count=fresh.requests.length; fresh.eck.refresh(true); fresh.requests[count].reply({results:[movie(88)]}); fresh.requests[count+1].reply({},503); fresh.requests[count+2].reply({},503);
assert.strictEqual(fresh.eck.list()[0].id,88); assert(fresh.eck.error().indexOf('Bazı')>=0);
console.log('EckCoreTest: 30 unique recommendations, newest-first ordering, future exclusion and partial network recovery passed.');
var independent = core(), scopes = [];
independent.eck.onChange(function(scope){scopes.push(scope);});
independent.eck.refresh(false); independent.eck.showcase.refresh(false);
assert.strictEqual(independent.requests.length,3,'same unfiltered feed requests coalesce');
var diverse = [movie(1),movie(2),movie(3),movie(4)];
diverse[0].genre_ids=[28];diverse[1].genre_ids=[28];diverse[2].genre_ids=[35];diverse[3].genre_ids=[27];
replyBatch(independent,0,diverse);
assert.strictEqual(independent.eck.showcase.list().map(function(m){return m.id;}).join(','),'1,3,4,2','showcase mixes genres independently');
var showcaseIds=independent.eck.showcase.list().map(function(m){return m.id;}).join(',');
scopes.length=0;independent.eck.setGenre(28);independent.eck.setGenre(35);independent.eck.setGenre(28);
assert.strictEqual(independent.requests.length,9,'rapid return to an in-flight genre coalesces');
replyBatch(independent,3,[movie(100)]);replyBatch(independent,6,[movie(200)]);
assert.strictEqual(independent.eck.list()[0].id,100,'late superseded genre cannot replace the current list');
assert.strictEqual(independent.eck.showcase.list().map(function(m){return m.id;}).join(','),showcaseIds);
assert(scopes.every(function(s){return s==='catalog';}),'genre events never update showcase');
var saved=core(independent.memory);assert.strictEqual(saved.eck.genre(),28);saved.eck.refresh(false);assert.strictEqual(saved.requests.length,0,'genre and feed survive restart');
count=independent.requests.length;independent.eck.showcase.refresh(true);replyBatch(independent,count,[],503);
assert.strictEqual(independent.eck.list()[0].id,100);assert(independent.eck.showcase.error());
count=independent.requests.length;independent.eck.refresh(true);replyBatch(independent,count,[],503);
assert.strictEqual(independent.eck.showcase.list().map(function(m){return m.id;}).join(','),showcaseIds);
assert.strictEqual(Object.keys(independent.memory).filter(function(k){return k.indexOf('movies.')>=0;}).length,2);
console.log('EckCoreTest: independent feeds, diversity, shared request/cache reuse, rapid genres, persistence and isolated failures passed.');
