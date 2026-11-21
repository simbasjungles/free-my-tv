/* ECK TV's bounded local preferences, movie lists and trailer selection. ES5 for Tizen 4. */
var ECK = (function () {
  'use strict';
  var PREFIX = 'eck.', listeners = [], discoveryCache = [], pendingDiscovery = {};
  var GENRES = [[28, 'Aksiyon'], [12, 'Macera'], [16, 'Animasyon'], [35, 'Komedi'], [80, 'Suç'],
    [99, 'Belgesel'], [18, 'Dram'], [10751, 'Aile'], [14, 'Fantastik'], [36, 'Tarih'],
    [27, 'Korku'], [10402, 'Müzik'], [9648, 'Gizem'], [10749, 'Romantik'], [878, 'Bilim kurgu'],
    [53, 'Gerilim'], [10752, 'Savaş'], [37, 'Western']];
  function read(key, fallback) {
    try { var v = JSON.parse(localStorage.getItem(PREFIX + key)); return v === null ? fallback : v; }
    catch (e) { return fallback; }
  }
  function write(key, value) { try { localStorage.setItem(PREFIX + key, JSON.stringify(value)); } catch (e) {} }
  function emit(scope) { for (var i = 0; i < listeners.length; i++) listeners[i](scope || 'catalog'); }
  function genre() {
    var id = +read('catalogGenre', 0);
    return GENRES.some(function (g) { return g[0] === id; }) ? id : 0;
  }
  function request(url, headers, done) {
    var xhr = new XMLHttpRequest(), over = false;
    function finish(err, body) { if (over) return; over = true; done(err, body); }
    xhr.open('GET', url, true); xhr.timeout = 9000;
    for (var h in headers) if (headers.hasOwnProperty(h)) xhr.setRequestHeader(h, headers[h]);
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      if (xhr.status !== 200) { finish(new Error('connection')); return; }
      try { var body = JSON.parse(xhr.responseText); } catch (e) { finish(new Error('response')); return; }
      finish(null, body);
    };
    xhr.onerror = xhr.ontimeout = function () { finish(new Error('connection')); };
    xhr.send();
  }
  function tmdb(path, done) {
    var key = Prefs.tmdbKey(), bearer = key.indexOf('eyJ') === 0;
    if (!key) { done(new Error('key')); return; }
    request('https://api.tmdb.org/3' + path + (bearer ? '' : (path.indexOf('?') < 0 ? '?' : '&') + 'api_key=' + encodeURIComponent(key)),
      bearer ? {Authorization: 'Bearer ' + key} : {}, done);
  }
  function cleanMovie(m) {
    return {id: +m.id, title: String(m.title || '').slice(0, 160), original: String(m.original_title || '').slice(0, 160),
      year: String(m.release_date || '').slice(0, 4), released: String(m.release_date || '').slice(0, 10), overview: String(m.overview || '').slice(0, 850),
      backdrop: m.backdrop_path || '', poster: m.poster_path || '', rating: Number(m.vote_average || 0)};
  }
  function hidden() { var v = read('hidden', []); return v instanceof Array ? v : []; }
  function favourites() { var v = read('favourites', []); return v instanceof Array ? v.slice(0, 80) : []; }
  function isFavourite(id) { return favourites().some(function (m) { return m.id === id; }); }
  function toggleFavourite(m) {
    var had = isFavourite(m.id), list = favourites().filter(function (x) { return x.id !== m.id; });
    if (!had) list.unshift(m);
    write('favourites', list.slice(0, 80)); emit();
  }
  // Identical requests from the two feeds share transport, never selection or feed state.
  function discover(path, force, done) {
    var key = Prefs.tmdbKey() + ':' + path, now = Date.now();
    var cached = discoveryCache.filter(function (x) { return x.key === key && now >= x.at && now - x.at < 6 * 3600000; })[0];
    if (pendingDiscovery[key]) { pendingDiscovery[key].push(done); return; }
    if (!force && cached) { done(null, cached.body); return; }
    pendingDiscovery[key] = [done];
    tmdb(path, function (err, body) {
      var waiting = pendingDiscovery[key]; delete pendingDiscovery[key];
      if (!err && body && body.results instanceof Array) {
        discoveryCache = discoveryCache.filter(function (x) { return x.key !== key; });
        discoveryCache.push({key: key, at: Date.now(), body: body});
        discoveryCache = discoveryCache.slice(-12);
      }
      waiting.forEach(function (fn) { fn(err, body); });
    });
  }
  function varied(list) {
    var remaining = list.slice(), used = {}, result = [];
    while (remaining.length && result.length < 30) {
      var best = 0;
      for (var i = 1; i < remaining.length; i++) {
        var a = (remaining[i].genre_ids || [0])[0] || 0, b = (remaining[best].genre_ids || [0])[0] || 0;
        if ((used[a] || 0) < (used[b] || 0)) best = i;
      }
      var m = remaining.splice(best, 1)[0], group = (m.genre_ids || [0])[0] || 0;
      used[group] = (used[group] || 0) + 1; result.push(m);
    }
    return result;
  }
  function movieFeed(scope) {
    var generation = 0, busy = false, movies = [], error = '', active = '';
    function refresh(force) {
    var filter = scope === 'catalog' ? genre() : 0, key = 'split30:' + filter;
    if (busy && active === key && !force) return;
    var mine = ++generation, cache = read('movies.' + scope, null); active = key;
    if (cache && cache.stamp !== key) cache = null;
    busy = false;
    if (cache && cache.list instanceof Array) movies = cache.list;
    else movies = [];
    error = '';
    if (!Prefs.tmdbKey()) { error = 'Filmler için Ayarlar’dan TMDB anahtarını ekle.'; emit(scope); return; }
    if (!force && cache && Date.now() - cache.at >= 0 && Date.now() - cache.at < 6 * 3600000) { emit(scope); return; }
    busy = true; emit(scope);
    var today = new Date(), since = new Date(today.getTime()); since.setMonth(since.getMonth() - 18);
    function date(d) { return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2); }
    var base = '/discover/movie?language=tr-TR&region=TR&sort_by=popularity.desc&include_adult=false&include_video=false&vote_count.gte=20&primary_release_date.lte=' + date(today)
      + (filter ? '&with_genres=' + filter : '');
    var recent = base + '&primary_release_date.gte=' + date(since), pending = 3, received = [], successes = 0;
    function finish(err, res) {
      if (mine !== generation || active !== key) return;
      if (!err && res && res.results instanceof Array) { successes++; received = received.concat(res.results); }
      if (--pending) return;
      busy = false;
      if (!successes) {
        error = movies.length ? 'Çevrimdışı · son kaydedilen liste' : 'Filmler yüklenemedi. Bağlantıyı ve TMDB anahtarını kontrol et.';
      } else {
        var ids = {}, off = hidden();
        var eligible = received.filter(function (m) {
          if (!m.id || !m.title || !m.backdrop_path || m.adult || ids[m.id] || off.indexOf(+m.id) >= 0 || !m.release_date || m.release_date > date(today)) return false;
          ids[m.id] = true; return true;
        });
        movies = (scope === 'showcase' ? varied(eligible) : eligible.sort(function (a, b) {
          return a.release_date === b.release_date ? Number(b.popularity || 0) - Number(a.popularity || 0) : a.release_date < b.release_date ? 1 : -1;
        }).slice(0, 30)).map(cleanMovie);
        error = successes < 3 ? 'Bazı öneriler yenilenemedi · mevcut sonuçlar gösteriliyor' : '';
        // One persistent slot per independent feed; the shared in-memory query cache is bounded too.
        write('movies.' + scope, {stamp: key, at: Date.now(), list: movies});
      }
      emit(scope);
    }
    // Two recent pages plus a popular backfill, without any streaming-provider restriction.
    discover(recent + '&page=1', force, finish); discover(recent + '&page=2', force, finish); discover(base + '&page=1', force, finish);
    }
    return {refresh: refresh, busy: function () { return busy; }, error: function () { return error; },
      list: function () { var off = hidden(); return movies.filter(function (m) { return off.indexOf(m.id) < 0; }); }};
  }
  var catalog = movieFeed('catalog'), showcase = movieFeed('showcase');
  // Prefer an actual Turkish trailer over an English trailer; reject teasers, clips and non-YouTube entries.
  function bestTrailer(videos) {
    var candidates = (videos || []).filter(function (v) {
      return v.site === 'YouTube' && v.type === 'Trailer' && /^[A-Za-z0-9_-]{11}$/.test(v.key || '');
    });
    candidates.sort(function (a, b) {
      function score(v) { return (v.iso_639_1 === 'tr' ? 100 : v.iso_639_1 === 'en' ? 30 : 0) + (v.official ? 15 : 0); }
      return score(b) - score(a);
    });
    return candidates.length ? candidates[0].key : '';
  }
  function searchQuery(movie) {
    var title = String(movie.title || movie.original || '').replace(/["\r\n]/g, ' ').replace(/\s+/g, ' ').trim();
    return '"' + title + '" ' + (/^\d{4}$/.test(movie.year || '') ? movie.year + ' ' : '') + 'resmi fragman Türkçe';
  }
  function trailer(movie, done) {
    tmdb('/movie/' + movie.id + '/videos?language=tr-TR&include_video_language=tr,en,null', function (err, res) {
      var id = !err && res ? bestTrailer(res.results) : '';
      if (id) { done(id, searchQuery(movie)); return; }
      tmdb('/movie/' + movie.id + '/videos?language=en-US', function (e, r) {
        done(!e && r ? bestTrailer(r.results) : '', searchQuery(movie));
      });
    });
  }
  // Only a handful of Image objects stay decoded. Persist metadata, never image base64 in localStorage.
  function imagePool(limit) {
    var pool = [], serial = 0;
    function preload(url, done) {
      var found = pool.filter(function (p) { return p.url === url; })[0];
      if (found) { if (found.ready) done(true); else found.waiters.push(done); return; }
      var p = {url: url, image: new Image(), waiters: [done], ready: false, token: ++serial};
      var timer = setTimeout(function () { finish(false); }, 12000), finished = false;
      function finish(ok) {
        if (finished) return; finished = true; clearTimeout(timer); p.ready = ok;
        p.image.onload = p.image.onerror = null;
        var waiting = p.waiters; p.waiters = [];
        if (!ok) pool = pool.filter(function (x) { return x !== p; });
        waiting.forEach(function (fn) { fn(ok); });
      }
      p.cancel = function () { finish(false); p.image.onload = p.image.onerror = null; p.image.src = ''; };
      pool.push(p);
      while (pool.length > limit) { var old = pool.shift(); old.cancel(); }
      p.image.onload = function () { finish(true); }; p.image.onerror = function () { finish(false); }; p.image.src = url;
    }
    return {load: preload, size: function () { return pool.length; }};
  }
  /** Fixed shelf; keep the installed id, never guess an app id when a service is absent. */
  function launcherApps(installed) {
    var definitions = [
      ['youtube', 'YouTube', /youtube/i, /kids/i, 'youtube.png', '#182139'],
      ['netflix', 'Netflix', /netflix/i, null, 'eck-netflix.png', '#111111'],
      ['disney', 'Disney+', /disney/i, null, 'eck-disney.png', '#002a35'],
      ['prime', 'Prime Video', /prime.?video|amazonignitionlauncher|amazon_video|amazon instant video|amazon video|^amazon /i, null, 'eck-prime.png', '#086ba0'],
      ['hbo', 'HBO Max', /hbo|max|wbd/i, null, 'eck-hbo-max.jpg', '#000000'],
      ['internet', 'İnternet', /browser|internet|taray.c./i, /knox|win365/i, 'eck-internet.svg', '#233258'],
      ['apple', 'Apple TV', /apple.?tv|appletv/i, null, 'eck-apple-tv.jpg', '#000000'],
      ['spotify', 'Spotify', /spotify/i, null, 'eck-spotify.png', '#101513']
    ];
    return definitions.map(function (d) {
      var matches = installed.filter(function (a) {
        var s = a.id + ' ' + a.title;
        if (d[0] === 'hbo') return /^(hbo\s*)?max$|^hbo\s*go$/i.test(a.title) || /hbo|wbd|\.max(?:\.|$)/i.test(a.id);
        return d[2].test(s) && (!d[3] || !d[3].test(s));
      });
      var a = matches[0];
      return {id: a ? a.id : null, key: d[0], title: d[1], icon: 'icons/' + d[4], color: d[5], available: !!a};
    });
  }
  function sceneStart(list, last, random) {
    var choices = [];
    for (var i = 0; i < list.length; i++) if (list[i].url !== last) choices.push(i);
    if (!choices.length) return 0;
    return choices[Math.min(choices.length - 1, Math.floor((random || Math.random)() * choices.length))];
  }
  return {
    genres: GENRES, genre: genre,
    setGenre: function (id) {
      var valid = GENRES.some(function (g) { return g[0] === +id; });
      write('catalogGenre', valid ? +id : 0); catalog.refresh(false);
    },
    list: catalog.list, refresh: catalog.refresh, busy: catalog.busy, error: catalog.error,
    showcase: showcase,
    onChange: function (fn) { listeners.push(fn); }, favourites: favourites, isFavourite: isFavourite,
    toggleFavourite: toggleFavourite,
    hide: function (id) { var list = hidden(); if (list.indexOf(id) < 0) list.push(id); write('hidden', list.slice(-200)); emit(); },
    resetHidden: function () { write('hidden', []); emit(); },
    read: function (key, fallback) { return read(key, fallback); }, write: write,
    sceneStart: sceneStart,
    launcherApps: launcherApps,
    bestTrailer: bestTrailer, searchQuery: searchQuery, trailer: trailer, request: request, imagePool: imagePool
  };
})();
