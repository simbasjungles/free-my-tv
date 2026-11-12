/*
 * Trakt (trakt.tv) on LG and Samsung, as Trakt.java on Android: only for someone who signs in with their own account,
 * and it only reads. The picks start with what they're in the middle of (a paused title, a show's next episode), bring
 * in their watchlist and Trakt's recommendations, and leave out what they've watched (data.js). Needs the person's own
 * API app (config.properties "trakt_client_id"); without it, or until they sign in, nothing here runs.
 *
 * Signing in is Trakt's device code with PKCE: Home Settings > Trakt shows a short code to type at trakt.tv/activate
 * on a phone. Only the Client ID is needed, so no secret is built into the package. The tokens stay in this app's
 * localStorage (fmtv.trakt), only sent to api.trakt.tv, never logged.
 */
var Trakt = (function () {
  'use strict';
  var API = 'https://api.trakt.tv', ID = String((window.CONFIG || {}).traktClientId || '').trim(), KEY = 'fmtv.trakt';
  // As on Android: watchlist titles and recommendations read per kind, and the shows watched most recently whose next
  // episode is looked up (one request each).
  var WATCHLIST_MAX = 12, RECOMMENDED_MAX = 10, UP_NEXT_SHOWS = 8;
  // Trakt's sync lists come in pages: 100 by default, 250 at most however many are asked (a long history was cut at
  // its first 100). Every page is read, up to PAGES_MAX.
  var PAGE = 250, PAGES_MAX = 40;
  var state = {code: '', url: '', message: ''}, busy = false, listeners = [];

  function load() { try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { return {}; } }
  function save(v) { try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) {} }
  function changed() { for (var i = 0; i < listeners.length; i++) listeners[i](); }

  /** SHA-256 of an ASCII string, as bytes: ES5 TVs have no crypto.subtle, and only the PKCE challenge needs it. */
  function sha256(text) {
    var K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
      0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
      0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }
    var bytes = [], i, t;
    for (i = 0; i < text.length; i++) bytes.push(text.charCodeAt(i) & 255);
    var bits = bytes.length * 8;
    bytes.push(0x80);
    while (bytes.length % 64 !== 56) bytes.push(0);
    bytes.push(0, 0, 0, 0, (bits >>> 24) & 255, (bits >>> 16) & 255, (bits >>> 8) & 255, bits & 255);
    var w = [];
    for (var o = 0; o < bytes.length; o += 64) {
      for (t = 0; t < 16; t++) w[t] = (bytes[o + 4 * t] << 24) | (bytes[o + 4 * t + 1] << 16) | (bytes[o + 4 * t + 2] << 8) | bytes[o + 4 * t + 3];
      for (t = 16; t < 64; t++) {
        var x = w[t - 15], y = w[t - 2];
        w[t] = (w[t - 16] + (rotr(x, 7) ^ rotr(x, 18) ^ (x >>> 3)) + w[t - 7] + (rotr(y, 17) ^ rotr(y, 19) ^ (y >>> 10))) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (t = 0; t < 64; t++) {
        var t1 = (h + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[t] + w[t]) | 0;
        var t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    var out = [];
    for (i = 0; i < 8; i++) out.push((H[i] >>> 24) & 255, (H[i] >>> 16) & 255, (H[i] >>> 8) & 255, H[i] & 255);
    return out;
  }

  function base64url(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  /** n random bytes: the TV's crypto where it has one (the PKCE secret), else Math.random. */
  function random(n) {
    var out = [], i;
    try {
      var a = new Uint8Array(n);
      window.crypto.getRandomValues(a);
      for (i = 0; i < n; i++) out.push(a[i]);
    } catch (e) {
      for (i = 0; i < n; i++) out.push(Math.floor(Math.random() * 256));
    }
    return out;
  }

  /** One request: done(status, json or null); 0 when it never answered. */
  function send(method, path, body, token, done) {
    var x = new XMLHttpRequest(), over = false;
    x.open(method, API + path, true);
    x.timeout = 15000;
    x.setRequestHeader('Content-Type', 'application/json');
    x.setRequestHeader('trakt-api-version', '2');
    x.setRequestHeader('trakt-api-key', ID);
    if (token) x.setRequestHeader('Authorization', 'Bearer ' + token);
    x.onreadystatechange = function () {
      if (x.readyState !== 4 || over) return;
      over = true;
      var json = null;
      try { json = x.responseText ? JSON.parse(x.responseText) : null; } catch (e) {}
      done(x.status, json);
    };
    x.ontimeout = x.onerror = function () { if (!over) { over = true; done(0, null); } };
    x.send(body ? JSON.stringify(body) : null);
  }

  function keep(t, user) {
    var v = load(), created = t.created_at || Math.floor(Date.now() / 1000);
    v.access = t.access_token;
    v.refresh = t.refresh_token;
    v.expires = (created + (t.expires_in || 86400)) * 1000;
    if (user !== undefined) v.user = user;
    save(v);
  }

  // MARK: - Sign in and out

  /** Asks Trakt for a code (state.code, to type at state.url), then waits for it; an expired code makes a new one. */
  function signIn() {
    if (!ID || busy || signedIn()) return;
    busy = true;
    state.message = '';
    state.code = '';
    changed();
    // PKCE: a one-time secret for this sign-in; Trakt gets its hash now and the secret itself with the code.
    var verifier = base64url(random(48)), challenge = base64url(sha256(verifier));
    send('POST', '/oauth/device/code', {client_id: ID, code_challenge: challenge, code_challenge_method: 'S256'}, '', function (status, start) {
      if (status !== 200 || !start) {
        busy = false;
        state.message = trf("Couldn't sign in: {error}", {error: status ? 'HTTP ' + status : tr('No connection')});
        changed();
        return;
      }
      state.code = start.user_code;
      state.url = String(start.verification_url || 'https://trakt.tv/activate').replace(/^https?:\/\//, '');
      changed();
      var interval = Math.max(5, start.interval || 5) * 1000, until = Date.now() + (start.expires_in || 600) * 1000;
      (function poll() {
        if (!busy) return;   // signed out (or cancelled) meanwhile
        if (Date.now() > until) { busy = false; signIn(); return; }
        setTimeout(function () {
          send('POST', '/oauth/device/token', {code: start.device_code, client_id: ID, code_verifier: verifier}, '', function (s, t) {
            if (!busy) return;
            if (s === 200 && t) {
              keep(t, '');
              state.code = '';
              send('GET', '/users/settings', null, t.access_token, function (s2, me) {
                var v = load();
                v.user = s2 === 200 && me && me.user ? me.user.username || '' : '';
                save(v);
                busy = false;
                changed();
              });
              return;
            }
            if (s === 429) interval += 1000;   // slow down
            if (s === 418) { busy = false; state.code = ''; state.message = tr('Sign-in declined on Trakt.'); changed(); return; }
            if (s === 410) until = 0;   // expired: a new code
            poll();   // 400: not typed yet
          });
        }, interval);
      })();
    });
  }

  function signedIn() { return !!ID && !!load().refresh; }

  /** Signed out here, and the token revoked on Trakt (best effort: offline, it just lapses). */
  function signOut() {
    var v = load();
    busy = false;
    state.code = '';
    state.message = '';
    save({});
    if (v.access) send('POST', '/oauth/revoke', {token: v.access, client_id: ID}, '', function () {});
    changed();
  }

  // A renewal in flight: the requests that need a token wait for it, so the refresh token is spent once (two renewals
  // at a time would send the same one twice, and Trakt would refuse the second and sign the TV out).
  var renewing = null;

  /** A valid access token, renewed when it's about to expire (Trakt's last a day); done(error, token). */
  function token(done) {
    var v = load();
    if (!v.refresh) { done(new Error('not signed in')); return; }
    if (Date.now() < v.expires - 3600000) { done(null, v.access); return; }
    if (renewing) { renewing.push(done); return; }
    renewing = [done];
    var refresh = v.refresh;
    send('POST', '/oauth/token', {refresh_token: refresh, client_id: ID, redirect_uri: 'urn:ietf:wg:oauth:2.0:oob',
        grant_type: 'refresh_token'}, '', function (s, t) {
      var waiting = renewing, err = null, access = '';
      renewing = null;
      if (load().refresh !== refresh) err = new Error('not signed in');   // signed out (or in again) meanwhile
      else if (s === 400 || s === 401) {
        // Trakt refused the refresh token (the app was disconnected on trakt.tv): signed out here too. Only an answer
        // can say so: no answer (the network down as the TV wakes) leaves the tokens for next time.
        save({});
        err = new Error('Trakt signed this TV out');
        changed();
      } else if (s !== 200 || !t) err = new Error('Trakt returned HTTP ' + s);
      else { keep(t); access = t.access_token; }
      for (var i = 0; i < waiting.length; i++) waiting[i](err, access);
    });
  }

  function get(path, done) {
    token(function (err, access) {
      if (err) { done(err); return; }
      send('GET', path, null, access, function (s, json) { done(s === 200 ? null : new Error('Trakt returned HTTP ' + s), json); });
    });
  }

  /** Every page of a sync list; done(error, items), the error only when the first page failed. */
  function getAll(path, done) {
    var all = [], page = 1, sep = path.indexOf('?') >= 0 ? '&' : '?';
    (function one() {
      get(path + sep + 'limit=' + PAGE + '&page=' + page, function (err, a) {
        if (err || !a) { done(page === 1 ? err || new Error('empty') : null, all); return; }
        all = all.concat(a);
        if (a.length < PAGE || ++page > PAGES_MAX) { done(null, all); return; }
        one();
      });
    })();
  }

  /** {"movie": {"ids": {"tmdb": 603}}} or {"ids": …} → "movie:603" (Trakt's show is TMDB's tv); '' without one. */
  function key(item, type) {
    var o = item && item[type] ? item[type] : item, tmdb = o && o.ids ? o.ids.tmdb : 0;
    return tmdb > 0 ? (type === 'show' ? 'tv' : 'movie') + ':' + tmdb : '';
  }

  function byDate(field) { return function (x, y) { return x[field] < y[field] ? 1 : x[field] > y[field] ? -1 : 0; }; }

  // MARK: - Lists

  /**
   * done(error, lists): {watched: {key: true}, progress: [{key, season, episode, paused}], watchlistMovies,
   * watchlistShows, recommendedMovies, recommendedShows: [keys], activity}. One part failing leaves the others, as on
   * Android; the watched titles failing fails the whole (the picks would bring back what was seen).
   */
  function lists(done) {
    var out = {watched: {}, progress: [], watchlistMovies: [], watchlistShows: [], recommendedMovies: [], recommendedShows: [], activity: ''};
    var at = {}, shows = [];
    function has(k) { for (var i = 0; i < out.progress.length; i++) if (out.progress[i].key === k) return true; return false; }
    // When each was last watched or marked watched: marking a show watched "on its release date" leaves
    // last_watched_at in the past, but last_updated_at is when it was marked (as Trakt.java's watched).
    function watched(path, type, next) {
      getAll(path, function (err, a) {
        if (err) { done(err); return; }
        for (var i = 0; i < a.length; i++) {
          var k = key(a[i], type);
          if (!k) continue;
          var w = a[i].last_watched_at || '', u = a[i].last_updated_at || '';
          out.watched[k] = true;
          at[k] = w > u ? w : u;
          if (type === 'show' && a[i].show && a[i].show.ids) shows.push({key: k, at: at[k], trakt: a[i].show.ids.trakt});
        }
        next();
      });
    }
    // Movies and episodes stopped partway, newest first; one watched since it was paused is left out.
    function playback(next) {
      get('/sync/playback?limit=20', function (err, a) {
        a = !err && a ? a.slice().sort(byDate('paused_at')) : [];
        for (var i = 0; i < a.length; i++) {
          var movie = a[i].type === 'movie', k = key(a[i], movie ? 'movie' : 'show'), ep = movie ? null : a[i].episode;
          if (!k || has(k) || (at[k] && at[k] >= a[i].paused_at)) continue;
          out.progress.push({key: k, season: ep ? ep.season : 0, episode: ep ? ep.number : 0, paused: true});
        }
        next();
      });
    }
    // The next episode of the shows watched most recently, when one is out and not seen yet.
    function upNext(next) {
      var list = shows.sort(byDate('at')).filter(function (s) { return s.trakt > 0 && !has(s.key); }).slice(0, UP_NEXT_SHOWS), i = 0;
      (function one() {
        if (i >= list.length) { next(); return; }
        var s = list[i++];
        get('/shows/' + s.trakt + '/progress/watched?specials=false&count_specials=false', function (err, p) {
          var n = !err && p && p.next_episode;
          if (n && p.completed < p.aired) out.progress.push({key: s.key, season: n.season, episode: n.number, paused: false});
          one();
        });
      })();
    }
    // The watchlist, newest first (every page, then the first WATCHLIST_MAX).
    function listed(path, type, into, max, next) {
      (path.indexOf('/sync/') === 0 ? getAll : get)(path, function (err, a) {
        a = !err && a ? a.slice() : [];
        if (a.length && a[0].listed_at) a.sort(byDate('listed_at'));
        for (var i = 0; i < a.length && into.length < max; i++) {
          var k = key(a[i], type);
          if (k && !out.watched[k] && into.indexOf(k) < 0) into.push(k);
        }
        next();
      });
    }
    get('/sync/last_activities', function (err, acts) {
      out.activity = !err && acts ? acts.all || '' : '';
      watched('/sync/watched/movies', 'movie', function () {
        watched('/sync/watched/shows?extended=noseasons', 'show', function () {
          playback(function () {
            upNext(function () {
              listed('/sync/watchlist/movies', 'movie', out.watchlistMovies, WATCHLIST_MAX, function () {
                listed('/sync/watchlist/shows', 'show', out.watchlistShows, WATCHLIST_MAX, function () {
                  listed('/recommendations/movies?ignore_collected=true&limit=' + RECOMMENDED_MAX * 2, 'movie', out.recommendedMovies, RECOMMENDED_MAX, function () {
                    listed('/recommendations/shows?ignore_collected=true&limit=' + RECOMMENDED_MAX * 2, 'show', out.recommendedShows, RECOMMENDED_MAX, function () {
                      done(null, out);
                    });
                  });
                });
              });
            });
          });
        });
      });
    });
  }

  /** When the account last changed on Trakt (ISO 8601, one small request): done(error, activity). */
  function activity(done) {
    get('/sync/last_activities', function (err, acts) { done(err, !err && acts ? acts.all || '' : ''); });
  }

  return {
    /** The person put their own Trakt API app in config.properties. */
    available: function () { return !!ID; },
    signedIn: signedIn,
    /** The account's name ('' if not signed in or unknown). */
    user: function () { return signedIn() ? load().user || '' : ''; },
    /** Changes when the account does: the picks are fetched again. */
    stamp: function () { return signedIn() ? 'trakt:' + (load().user || '') : ''; },
    /** Signing in: the code to type at url, or '' while it's asked for. */
    get code() { return busy ? state.code : ''; },
    get url() { return state.url; },
    busy: function () { return busy; },
    /** How the last sign-in went ('' when there's nothing to say). */
    message: function () { return state.message; },
    signIn: signIn,
    signOut: signOut,
    lists: lists,
    activity: activity,
    onChange: function (fn) { listeners.push(fn); }
  };
})();
