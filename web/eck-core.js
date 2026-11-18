/* ECK TV's bounded local preferences, movie lists and trailer selection. ES5 for Tizen 4. */
var ECK = (function () {
  'use strict';
  var PREFIX = 'eck.', listeners = [], generation = 0, busy = false, movies = [], error = '';
  var GENRES = [[28, 'Aksiyon'], [12, 'Macera'], [16, 'Animasyon'], [35, 'Komedi'], [80, 'Suç'],
    [99, 'Belgesel'], [18, 'Dram'], [10751, 'Aile'], [14, 'Fantastik'], [36, 'Tarih'],
    [27, 'Korku'], [10402, 'Müzik'], [9648, 'Gizem'], [10749, 'Romantik'], [878, 'Bilim kurgu'],
    [53, 'Gerilim'], [10752, 'Savaş'], [37, 'Western']];
  function read(key, fallback) {
    try { var v = JSON.parse(localStorage.getItem(PREFIX + key)); return v === null ? fallback : v; }
    catch (e) { return fallback; }
  }
  function write(key, value) { try { localStorage.setItem(PREFIX + key, JSON.stringify(value)); } catch (e) {} }
  function emit() { for (var i = 0; i < listeners.length; i++) listeners[i](); }
  function mode() { return read('mode', 'popular') === 'genres' ? 'genres' : 'popular'; }
  function genres() {
    var kept = read('genres', [28, 878, 35]);
    return GENRES.filter(function (g) { return kept instanceof Array && kept.indexOf(g[0]) >= 0; }).map(function (g) { return g[0]; });
  }
  function stamp() { return mode() + ':' + genres().join(','); }
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
      year: String(m.release_date || '').slice(0, 4), overview: String(m.overview || '').slice(0, 850),
      backdrop: m.backdrop_path || '', poster: m.poster_path || '', rating: Number(m.vote_average || 0)};
  }
  function hidden() { var v = read('hidden', []); return v instanceof Array ? v : []; }
  function visible() { var off = hidden(); return movies.filter(function (m) { return off.indexOf(m.id) < 0; }); }
  function favourites() { var v = read('favourites', []); return v instanceof Array ? v.slice(0, 80) : []; }
  function isFavourite(id) { return favourites().some(function (m) { return m.id === id; }); }
  function toggleFavourite(m) {
    var had = isFavourite(m.id), list = favourites().filter(function (x) { return x.id !== m.id; });
    if (!had) list.unshift(m);
    write('favourites', list.slice(0, 80)); emit();
  }
  function refresh(force) {
    var mine = ++generation, cache = read('movies.' + mode(), null), key = stamp();
    if (cache && cache.stamp !== key) cache = null;
    busy = false;
    if (cache && cache.list instanceof Array) movies = cache.list;
    else movies = [];
    error = '';
    if (!Prefs.tmdbKey()) { error = 'Filmler için Ayarlar’dan TMDB anahtarını ekle.'; emit(); return; }
    if (!force && cache && Date.now() - cache.at >= 0 && Date.now() - cache.at < 6 * 3600000) { emit(); return; }
    if (mode() === 'genres' && !genres().length) { error = 'Ayarlar’dan en az bir tür seç.'; emit(); return; }
    busy = true; emit();
    var path = mode() === 'popular' ? '/movie/popular?language=tr-TR&region=TR&page=1'
      : '/discover/movie?language=tr-TR&region=TR&sort_by=popularity.desc&include_adult=false&vote_count.gte=50&with_genres=' + genres().join('|');
    tmdb(path, function (err, res) {
      if (mine !== generation || key !== stamp()) return;
      busy = false;
      if (err || !res || !(res.results instanceof Array)) {
        error = movies.length ? 'Çevrimdışı · son kaydedilen liste' : 'Filmler yüklenemedi. Bağlantıyı ve TMDB anahtarını kontrol et.';
      } else {
        movies = res.results.filter(function (m) { return m.id && m.title && m.backdrop_path && !m.adult; }).slice(0, 20).map(cleanMovie);
        // Two slots only: changing genres replaces its previous cache, rather than filling storage.
        write('movies.' + mode(), {stamp: key, at: Date.now(), list: movies});
      }
      emit();
    });
  }
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
  function selectMode(value) { write('mode', value === 'genres' ? 'genres' : 'popular'); refresh(false); }
  return {
    genres: GENRES, selectedGenres: genres, mode: mode, setMode: selectMode,
    toggleGenre: function (id) {
      var list = genres(), at = list.indexOf(id);
      if (at < 0) list.push(id); else list.splice(at, 1);
      write('genres', list);
    },
    list: visible, refresh: refresh, busy: function () { return busy; }, error: function () { return error; },
    onChange: function (fn) { listeners.push(fn); }, favourites: favourites, isFavourite: isFavourite,
    toggleFavourite: toggleFavourite,
    hide: function (id) { var list = hidden(); if (list.indexOf(id) < 0) list.push(id); write('hidden', list.slice(-200)); emit(); },
    resetHidden: function () { write('hidden', []); emit(); },
    read: function (key, fallback) { return read(key, fallback); }, write: write,
    bestTrailer: bestTrailer, searchQuery: searchQuery, trailer: trailer, request: request, imagePool: imagePool
  };
})();
