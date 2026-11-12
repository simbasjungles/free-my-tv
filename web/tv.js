/*
 * The TV's side of the web home screen: its apps, opening one, its inputs, its settings, the Back key, its time zone.
 *  - LG webOS: the TV's service bus (Luna), the same calls LG's launcher makes.
 *  - Samsung Tizen: Tizen's web APIs (tizen.application for the apps, tizen.tvwindow for the inputs).
 *  - A desktop browser: a stand-in with sample apps, so the screen can be previewed and tested on a computer.
 * Plain ES5: older TVs run a Chromium from 2015-2017 (webOS 3 and 4, Tizen 3).
 */
var TV = (function () {
  'use strict';

  var webos = typeof window.PalmServiceBridge !== 'undefined';
  var samsung = !webos && typeof window.tizen !== 'undefined' && !!window.tizen.application;
  var calls = [];   // a bridge is collected if nothing holds it before its reply arrives
  var tizen2 = samsung && /Tizen 2\./.test(navigator.userAgent);
  // 2017 and 2018 Samsungs (Tizen 3 and 4) freeze opening their settings from an app (MU6100, MU6122, NU7400, #257).
  var tizen34 = samsung && /Tizen [34]\./.test(navigator.userAgent);
  // Tizen 2 (2016 TVs, WebKit 538) repaints every shadow on every frame of a focus animation: home.css turns them off.
  // So do LGs under 2 GB: an LG on webOS TV Lite 10 (MT5889, 1.3 GB, ratio 2) ran the shelf at 23-28 fps, 60 with lite
  // and 45 with only the shadows off (#318); a 43LR6700PSA on webOS TV Lite 8.5 (1.5 GB, ratio 1) at 45-50, 59 with
  // lite and 55 with only the shadows off (#383). Samsungs at that memory too: a 2021 UN50AU7700 (Tizen 6, 1.5 GB, ratio
  // 1) still stutters at each move with flat alone (#461). deviceMemory (webOS 5+, Tizen 5+) is the nearest power of two,
  // so it reads 1 up to about 1.5 GB.
  var lowMemory = navigator.deviceMemory && navigator.deviceMemory <= 1;
  // webOS 1 to 3 (2014-2017, Chromium 38) have no deviceMemory, and their sets have 1 to 1.5 GB: lite too.
  var chrome = /Chrome\/(\d+)/.exec(navigator.userAgent);
  var oldWebos = webos && !navigator.deviceMemory && chrome && parseInt(chrome[1], 10) < 53;
  if (tizen2 || oldWebos || ((webos || samsung) && lowMemory)) {
    // webOS 3 on a 4K set (UH6150) re-rasterized ~300 ms of tiles at each move; with each tile and pick on its own
    // compositor layer that fell to ~8 ms, and the focus can animate again (`layered` in home.css).
    document.documentElement.className += oldWebos ? ' lite layered' : ' lite';
  } else if (samsung && navigator.deviceMemory && navigator.deviceMemory <= 2) {
    // Samsungs up to 2 GB keep the animations but not the soft shadows of the parts that move (home.css): a 2021 Frame
    // (QE55LS03A, Tizen 6, 2 GB, ratio 1) froze for up to 360 ms on each move along the picks and dropped 78 frames in
    // 8 moves along the shelf; with flat, at most 75 ms and 13 frames. Not with lite, which has no shadows or
    // animations to spare, and where the layers flat asks for would only take memory.
    document.documentElement.className += ' flat';
  }

  /** One Luna call; done(result) with null on failure. */
  function luna(uri, params, done) {
    if (!webos) {
      if (done) setTimeout(function () { done(null); }, 0);
      return;
    }
    var bridge = new window.PalmServiceBridge();
    calls.push(bridge);
    var finished = false;
    bridge.onservicecallback = function (message) {
      if (finished) return;
      finished = true;
      var i = calls.indexOf(bridge);
      if (i >= 0) calls.splice(i, 1);
      var result = null;
      try { result = JSON.parse(message); } catch (e) {}
      if (done) done(result && result.returnValue !== false ? result : null);
    };
    bridge.call(uri, JSON.stringify(params || {}));
  }

  // LG's own screensaver asks every subscribed app first. While Free My TV is on screen and its own screensaver will
  // take over (`hold`, from home.js), it says no; otherwise, and in the background, the TV's runs as before.
  // Only where the TV lets the app subscribe: webOS 3.4 denies the call to Developer Mode apps (getPowerState too), so
  // there the one reply is that refusal, with no timestamp: nothing is held, and saverBridge stays set, so it isn't
  // tried again. A 60UH6150 didn't start its own screensaver over Free My TV anyway (#478).
  var saverBridge = null;

  function holdScreenSaver(hold) {
    if (!webos || saverBridge) return;
    saverBridge = new window.PalmServiceBridge();
    saverBridge.onservicecallback = function (message) {
      var m = null;
      try { m = JSON.parse(message); } catch (e) {}
      if (!m || m.timestamp === undefined) return;
      luna('luna://com.webos.service.tvpower/power/responseScreenSaverRequest',
          {clientName: self, ack: !!document.hidden || !hold(), timestamp: m.timestamp});
    };
    saverBridge.call('luna://com.webos.service.tvpower/power/registerScreenSaverRequest',
        JSON.stringify({subscribe: true, clientName: self}));
  }

  // webOS 6 gives Developer Mode apps only the "public" group, which leaves out listLaunchPoints. There the list comes
  // from the TV's remote control API on 127.0.0.1:3000 (the one LG's phone app uses), which takes file:// pages.
  // The TV asks once to allow it; the key it gives back is kept. Newer firmware gives the page its own origin
  // (file://<app id>-webos), which the API refuses with 1008 "invalid origin": there our JS service (webos/service)
  // makes the call.
  var SSAP_KEY = 'fmtv.ssapKey', SSAP_REFUSED = 'fmtv.ssapRefused';

  /** One call to the remote control API; done(result) with null on failure. */
  function ssap(uri, done) {
    var ws = null, finished = false, key = null;
    var timer = setTimeout(function () { finish(null); }, 90000);   // time to answer the TV's prompt
    function finish(result) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      try { ws.close(); } catch (e) {}
      done(result);
    }
    try { key = localStorage.getItem(SSAP_KEY); } catch (e) {}
    try { ws = new WebSocket('ws://127.0.0.1:3000'); } catch (e) { finish(null); return; }
    ws.onopen = function () {
      var payload = {forcePairing: false, pairingType: 'PROMPT',
        manifest: {manifestVersion: 1, appVersion: '1.0', permissions: ['READ_INSTALLED_APPS']}};
      if (key) payload['client-key'] = key;
      ws.send(JSON.stringify({type: 'register', id: 'register', payload: payload}));
    };
    ws.onmessage = function (event) {
      var m = null;
      try { m = JSON.parse(event.data); } catch (e) {}
      if (!m) return;
      if (m.type === 'registered') {
        try { localStorage.setItem(SSAP_KEY, m.payload['client-key']); } catch (e) {}
        ws.send(JSON.stringify({type: 'request', id: 'call', uri: 'ssap://' + uri}));
      } else if (m.id === 'call' || m.type === 'error') {
        if (m.id === 'register' && m.type === 'error') {
          try { localStorage.setItem(SSAP_REFUSED, '1'); } catch (e) {}   // the person said no on the TV
        }
        finish(m.type !== 'error' && m.payload && m.payload.returnValue !== false ? m.payload : null);
      }
    };
    ws.onerror = function () { finish(null); };
    ws.onclose = function (event) {
      if (event.code === 1008 && !finished) viaService(); else finish(null);
    };
    function viaService() {
      clearTimeout(timer);
      timer = setTimeout(function () { finish(null); }, 100000);   // the service waits 90 s for the prompt
      luna('luna://' + self + '.service/ssap', {uri: uri, clientKey: key}, function (res) {
        if (res && res.clientKey) {
          try { localStorage.setItem(SSAP_KEY, res.clientKey); } catch (e) {}
        }
        if (res && res.refused) {
          try { localStorage.setItem(SSAP_REFUSED, '1'); } catch (e) {}   // the person said no on the TV
        }
        finish(res && res.payload && res.payload.returnValue !== false ? res.payload : null);
      });
    }
  }

  // LG inputs: the antenna and the four HDMI ports, with the apps LG opens for them.
  var INPUTS = [
    {id: 'com.webos.app.livetv', key: 'live', label: null, symbol: 'live_tv'},
    {id: 'com.webos.app.hdmi1', key: 'hdmi1', label: 'HDMI 1', symbol: 'hdmi'},
    {id: 'com.webos.app.hdmi2', key: 'hdmi2', label: 'HDMI 2', symbol: 'hdmi'},
    {id: 'com.webos.app.hdmi3', key: 'hdmi3', label: 'HDMI 3', symbol: 'hdmi'},
    {id: 'com.webos.app.hdmi4', key: 'hdmi4', label: 'HDMI 4', symbol: 'hdmi'}
  ];

  // Not shown among LG's apps: the inputs above, LG's own home and launchers.
  var HIDDEN = /^(com\.webos\.app\.(livetv|hdmi\d|home|homeconnect|discovery|launcher)|com\.webos\.app\.inputpicker)$/;

  // Not shown among Samsung's apps: Smart Hub's own pieces, the platform's tools and players (but its browser,
  // Netflix and Prime Video, which live there too), services, keyboards and ad players. The rest is what Smart Hub shows.
  // Tizen 4 also lists Samsung's own pieces: the YouTube engine's hosts (cobalt-*: "App", "Youtube", "YoutubeTV"),
  // Bixby, search, previews, the weather and notification popups, Apple TV's stand-in and engine.
  // A 2020 TU8000 (Tizen 5.5) ships Prime Video as org.tizen.primevideo, next to a -dummy and a _companionapp.
  // A 2023 Q60C (Tizen 9 in Brazil) ships Globoplay as org.tizen.globoplayapp.
  // A 2023 CU7700 (Tizen 9) leaves `show` unset on ~500 apps: Samsung's system namespaces go, but for the store,
  // SmartThings, Apple TV (aria-video), TV Plus, Gaming Hub and Windows 365.
  var SAMSUNG_HIDDEN = new RegExp('^(org\\.volt\\.|org\\.tizen\\.(?!browser$|netflix|primevideo$|globoplay)|org\\.tizen\\.netflixlowmem$|ise-|ug-|d75857a5-)' +
      '|[Ss]ervice$|[Dd]aemon|Preview$|syspopup|[Hh]elper$|AdPlayer|AdSuite|[Kk]eyboard|\\.OTApp$' +
      '|^com\\.samsung\\.calendar-account-manager' +
      '|^com\\.samsung\\.tv\\.(?!store$|SmartThingsApp$|aria-video$|knox-browser-win365$|tvplus$|gaminghub$)' +
      '|^com\\.samsung\\.tizen\\.' +
      '|^com\\.samsung\\.(ep[-.]|rdm[-.]|SelfDiagnosis$|mtm-banner$|hitcomp$|video-test$)' +
      '|^com\\.sausng\\.' +
      '|^adplatform\\.' +
      '|^(unified-drm-|lib-|p2p-asm|mobilebff$|comss$|acm-watcher$|dmr_service_app$|aifw-|csapi-|capi-|ep-|test-|emergency-log-transmitter$|samsung-easy-support$)');

  // Samsung apps whose iconPath is Tizen's generic pinwheel (a valid image, so onerror never fires): our own icons.
  // Seen on a 2018 NU7675 (Tizen 4); Smart Hub draws their real icons from a folder apps can't read. On a 2017
  // MU6100 (Tizen 3) no iconPath loads at all, so common apps get a logo here: Simple Icons' (CC0), cropped and tinted
  // for the tiles, with a width and height so old WebKit sizes them like PNGs.
  var SAMSUNG_ICONS = {
    '9Ur5IzDKqV.TizenYouTube': 'icons/youtube.png',
    'evKhCgZelL.AmazonIgnitionLauncher2': 'icons/prime.png',
    'org.tizen.primevideo': 'icons/prime.png',
    'RN1MCdNq8t.Netflix': 'icons/netflix.svg',
    'org.tizen.netflix-app': 'icons/netflix.svg',
    'NvZ26VG9Yw.HBOGOLAG': 'icons/max.svg',
    'rJeHak5zRg.Spotify': 'icons/spotify.svg',
    'cexr1qp97S.Deezer': 'icons/deezer.svg',
    '5SLwh7m3En.TizenYouTubeKids': 'icons/youtubekids.svg',
    '4OAwqypfRT.RedBullTV': 'icons/redbull.svg',
    'UCJa2s9hhC.TED': 'icons/ted.svg',
    'QizQxC7CUf.PlayMovies': 'icons/googleplay.svg',
    'vIutycUzou.SteamLink': 'icons/steam.svg',
    'geUOM7qimf.FOXPlay': 'icons/fox.svg',
    'AprZAARz4r.Jellyfin': 'icons/jellyfin.svg'
  };

  // The same logos by name (lowercase letters and digits only), for app ids the list above doesn't know: older TVs
  // (a 2016 KU6300, Tizen 2.4) can carry other ids for the same apps.
  var SAMSUNG_ICON_NAMES = {
    youtube: 'icons/youtube.png', primevideo: 'icons/prime.png', amazonprimevideo: 'icons/prime.png',
    netflix: 'icons/netflix.svg', max: 'icons/max.svg', spotify: 'icons/spotify.svg',
    deezer: 'icons/deezer.svg', youtubekids: 'icons/youtubekids.svg', redbulltv: 'icons/redbull.svg', ted: 'icons/ted.svg',
    steamlink: 'icons/steam.svg', jellyfin: 'icons/jellyfin.svg', paramount: 'icons/paramountplus.svg',
    paramountplus: 'icons/paramountplus.svg', crunchyroll: 'icons/crunchyroll.svg', twitch: 'icons/twitch.svg',
    plex: 'icons/plex.svg', appletv: 'icons/appletv.svg'
  };

  // Names Smart Hub gets wrong: HBO's app has none (so it never reached the shelf), others are long or old.
  var SAMSUNG_TITLES = {
    'NvZ26VG9Yw.HBOGOLAG': 'Max',
    'rJeHak5zRg.Spotify': 'Spotify',
    'evKhCgZelL.AmazonIgnitionLauncher2': 'Prime Video',
    'org.tizen.globoplayapp': 'Globoplay'
  };

  // Samsung's settings menu on Tizen 3, which answers no "settings" app control.
  var SAMSUNG_MENU = 'org.tizen.menu';

  // Samsung's live TV viewer: it shows whichever source the TV is on (the antenna, or the HDMI picked here).
  var SAMSUNG_VIEWER = 'org.tizen.tv-viewer';

  var SAMPLE = [
    ['netflix', 'Netflix', '#E50914'], ['youtube.leanback.v4', 'YouTube', '#FF0033'], ['amazon', 'Prime Video', '#1A98FF'],
    ['com.disney.disneyplus-prod', 'Disney+', '#1838C8'], ['com.hbo.hbomax', 'Max', '#6C3BE0'],
    ['com.apple.appletv', 'Apple TV', '#B8BCC6'], ['globoplaywebos', 'Globoplay', '#FF5A1F'], ['spotify-beehive', 'Spotify', '#1DB954'],
    ['cdp-30', 'Plex', '#E5A00D'], ['com.crunchyroll.webapp', 'Crunchyroll', '#F47521'], ['com.webos.app.browser', 'Web Browser', '#3A7BD5'],
    ['com.palm.app.settings', 'Settings', '#8E8E93']
  ];

  // Newer webOS (11 tested) denies listLaunchPoints to Developer Mode apps, but still answers getAppLoadStatus for
  // one app id: these are checked one by one there. Their icons can't be read, so they get our logo where web/icons
  // has one, as on Samsung, and otherwise their name on the brand's color: [id, title, logo, color].
  var KNOWN = [
    ['netflix', 'Netflix', 'icons/netflix.svg'], ['youtube.leanback.v4', 'YouTube', 'icons/youtube.png'],
    ['amazon', 'Prime Video', 'icons/prime.png'], ['com.disney.disneyplus-prod', 'Disney+'],
    ['com.wbd.stream', 'HBO Max', 'icons/max.svg'], ['com.hbo.hbomax', 'Max', 'icons/max.svg'],
    ['com.apple.appletv', 'Apple TV'], ['hulu', 'Hulu'], ['com.peacock.tv', 'Peacock'], ['com.nfl.app', 'NFL'],
    ['globoplaywebos', 'Globoplay'], ['spotify-beehive', 'Spotify', 'icons/spotify.svg'],
    ['tv.twitch.tv.starshot.lg', 'Twitch', '', '#9146FF'], ['cdp-30', 'Plex', '', '#E5A00D'],
    ['org.jellyfin.webos', 'Jellyfin', 'icons/jellyfin.svg'], ['com.crunchyroll.webapp', 'Crunchyroll', '', '#F47521'],
    ['com.webos.app.browser', 'Web Browser', '', '#3A7BD5'], ['space.nuvio.webos', 'Nuvio TV', '', '#0E0F12']
  ];

  function knownApps(done) {
    var found = [], left = KNOWN.length, sent = false;
    function finish() {
      if (sent) return;
      sent = true;
      done(found.filter(function (x) { return x; }));
    }
    setTimeout(finish, 5000);   // an id that never answers doesn't hold the rest
    KNOWN.forEach(function (k, i) {
      luna('luna://com.webos.applicationManager/getAppLoadStatus', {appId: k[0]}, function (res) {
        if (res && res.exist) found[i] = {id: k[0], title: k[1], icon: k[2] || '', color: k[3] || ''};
        if (--left === 0) finish();
      });
    });
  }

  var self = 'io.freemytv';
  if (webos && window.PalmSystem && window.PalmSystem.identifier) self = window.PalmSystem.identifier.split(' ')[0];
  if (samsung) {
    try { self = window.tizen.application.getCurrentApplication().appInfo.id; } catch (e) {}
  }

  // MARK: - Samsung

  function samsungApps(done) {
    try {
      window.tizen.application.getAppsInfo(function (list) {
        var out = [];
        // App ids named in shelf_apps stay even with show false, and are looked up when the list leaves them out:
        // Moonlight, installed in Developer Mode on a 2023 Q60C (Tizen 9), never reached the shelf though Smart Hub
        // lists it. Only whole ids, so a name in shelf_apps can't bring back the platform's hidden pieces.
        var ids = ((window.CONFIG || {}).shelfApps || []).filter(function (w) { return w.indexOf('.') > 0; });
        for (var i = 0; i < list.length; i++) {
          var a = list[i];
          if (!a.id || a.id === self || SAMSUNG_HIDDEN.test(a.id) || (a.show === false && ids.indexOf(a.id) < 0)) continue;
          out.push(samsungApp(a));
        }
        ids.forEach(function (id) {
          if (out.some(function (x) { return x.id === id; })) return;
          try { out.push(samsungApp(window.tizen.application.getAppInfo(id))); } catch (e) {}
        });
        // The built-in Netflix stays only when the store's Netflix isn't installed.
        var store = out.some(function (x) { return /\.Netflix$/.test(x.id); });
        if (store) out = out.filter(function (x) { return x.id.indexOf('org.tizen.netflix') !== 0; });
        // Smart Hub keeps its own order, which isn't readable: the same alphabetical order as All Apps elsewhere.
        out.sort(function (x, y) { var a = x.title.toLowerCase(), b = y.title.toLowerCase(); return a < b ? -1 : a > b ? 1 : 0; });
        done(out);
      }, function () { done([]); });
    } catch (e) {
      done([]);
    }
  }

  function samsungApp(a) {
    var icon = a.iconPath || '';
    if (icon && icon.indexOf('://') < 0) icon = 'file://' + icon;
    var title = SAMSUNG_TITLES[a.id] || a.name || a.id;
    // Older Smart Hubs (a 2015 JU6075) still list Amazon's app under its old name, with another id.
    if (/^Amazon( Instant)? Video$/i.test(title)) title = 'Prime Video';
    var key = String(title).toLowerCase().replace(/[^a-z0-9]/g, '');
    var byName = Object.prototype.hasOwnProperty.call(SAMSUNG_ICON_NAMES, key) ? SAMSUNG_ICON_NAMES[key] : '';
    if (SAMSUNG_ICONS[a.id] || byName) icon = SAMSUNG_ICONS[a.id] || byName;
    var dial = key === 'netflix' ? 'Netflix' : key === 'youtube' ? 'YouTube' : /^(amazon)?primevideo$/.test(key) ? 'Amazon' : '';
    if (dial) samsungDial[a.id] = dial;
    return {id: a.id, title: title, icon: icon, color: ''};
  }

  /**
   * Built-in apps whose id starts with a number (11101200001.Netflix, 111299001912.YouTube and
   * 111299001578.Amazon_Video on a 2015 JU6075, Tizen 2.3) are native, and tizen.application.launch doesn't open them;
   * the TV's own DIAL server, which phones cast through, does.
   */
  var samsungDial = {}, NATIVE = /^\d+(\.|$)/;
  /**
   * Netflix on 2017 and 2018 Samsungs (Tizen 3 and 4): launch answers success and Netflix stays black, Back and all
   * (MU6100, MU6122, NU7400, #257). Smart Hub's own number for it, through the TV's REST API, opens it normally, as
   * phones do; the TV may ask once whether to allow it, so that call waits for the person.
   */
  var NETFLIX_HUB = '11101200001';

  function dialLaunch(name, done) {
    post('/ws/apps/' + name, 8000, done);
  }

  function hubLaunch(hubId, done) {
    post('/api/v2/applications/' + hubId, 30000, done);
  }

  /** A POST to the TV's own server on port 8001 (DIAL and its REST API); done(ok). */
  function post(path, timeout, done) {
    var x = new XMLHttpRequest(), over = false;
    x.open('POST', 'http://127.0.0.1:8001' + path, true);
    x.timeout = timeout;
    x.onreadystatechange = function () {
      if (x.readyState !== 4 || over) return;
      over = true;
      if (done) done(x.status === 200 || x.status === 201);
    };
    x.ontimeout = x.onerror = function () { if (!over) { over = true; if (done) done(false); } };
    x.setRequestHeader('Content-Type', 'text/plain; charset=utf-8');
    x.send('');
  }

  function samsungLaunch(id, done) {
    var dial = samsungDial[id];
    function launch(otherwise) {
      try {
        window.tizen.application.launch(id, function () { if (done) done(true); }, otherwise);
      } catch (e) {
        otherwise();
      }
    }
    function failed() { if (done) done(false); }
    if (tizen34 && dial === 'Netflix') {
      hubLaunch(NETFLIX_HUB, function (ok) { if (ok) { if (done) done(true); } else launch(function () { dialLaunch(dial, done); }); });
      return;
    }
    // A native id: DIAL first (launch doesn't open those on Tizen 2.3), and launch where DIAL doesn't answer.
    if (dial && NATIVE.test(id)) {
      dialLaunch(dial, function (ok) { if (ok) { if (done) done(true); } else launch(failed); });
      return;
    }
    launch(dial ? function () { dialLaunch(dial, done); } : failed);
  }

  /**
   * The video sources the TV reports ([{type: 'TV' | 'HDMI' | ..., number}]), or null when it doesn't tell. With `all`,
   * the disconnected ones too: Tizen 4 lists an HDMI device on standby as disconnected (even the one on screen), and
   * tvwindow.setSource still switches to it, as Samsung's own source menu does.
   */
  function samsungSources(done, all) {
    try {
      window.tizen.systeminfo.getPropertyValue('VIDEOSOURCE', function (v) {
        done(v && v.connected ? (all ? v.connected.concat(v.disconnected || []) : v.connected) : null);
      }, function () { done(null); });
    } catch (e) {
      done(null);
    }
  }

  /** Puts the TV on a source ("source:HDMI:2"), then brings the TV's viewer up to show it. */
  function samsungSource(spec, done) {
    var parts = spec.split(':'), type = parts[1], number = +parts[2];
    function show(ok) {
      if (!ok) { if (done) done(false); return; }
      samsungLaunch(SAMSUNG_VIEWER, function (shown) {
        if (!shown) {
          try { window.tizen.application.getCurrentApplication().hide(); } catch (e) {}
        }
        if (done) done(true);
      });
    }
    samsungSources(function (sources) {
      var source = null;
      for (var i = 0; sources && i < sources.length; i++) {
        if (sources[i].type === type && sources[i].number === number) source = sources[i];
      }
      if (!source) { show(type === 'TV'); return; }   // the antenna: the viewer alone brings it up
      try {
        window.tizen.tvwindow.setSource(source, function () { show(true); }, function () { show(false); });
      } catch (e) {
        show(false);
      }
    }, true);
  }

  function samsungInputs(done) {
    var list = [{id: 'source:TV:1', key: 'live', label: null, symbol: 'live_tv', connected: true}];
    for (var n = 1; n <= 4; n++) list.push({id: 'source:HDMI:' + n, key: 'hdmi' + n, label: 'HDMI ' + n, symbol: 'hdmi', connected: true});
    samsungSources(function (sources) {
      if (sources) {
        for (var i = 1; i < list.length; i++) list[i].connected = false;
        for (var j = 0; j < sources.length; j++) {
          var s = sources[j];
          if (s.type === 'HDMI' && s.number >= 1 && s.number <= 4) list[s.number].connected = true;
        }
      }
      done(list);
    });
  }

  /** Samsung's settings: whichever app answers the platform's "settings" request, else the TV's menu (Tizen 3). */
  function samsungSettings(done) {
    function menu() { samsungLaunch(SAMSUNG_MENU, done); }
    try {
      var control = new window.tizen.ApplicationControl('http://tizen.org/appcontrol/operation/setting', null, null, null, []);
      window.tizen.application.launchAppControl(control, null, function () { if (done) done(true); }, menu);
    } catch (e) {
      menu();
    }
  }

  // MARK: - About this TV

  /** done({model, firmware, system, version, ip}), with '' for what the TV doesn't tell. */
  function about(done) {
    var out = {model: '', firmware: '', system: '', version: '', ip: ''}, left = 1, sent = false;
    function wait() { left++; }
    function finish() {
      if (--left > 0 || sent) return;
      sent = true;
      clearTimeout(timer);
      done(out);
    }
    var timer = setTimeout(function () { left = 1; finish(); }, 4000);   // a service that never answers doesn't hold the rest

    if (webos) {
      // The services LG's webOSTV.js reads for webOS.deviceInfo, and its public connection manager.
      wait();
      luna('luna://com.webos.service.tv.systemproperty/getSystemInfo', {keys: ['modelName', 'firmwareVersion', 'sdkVersion']}, function (res) {
        if (res) {
          out.model = res.modelName || '';
          out.firmware = res.firmwareVersion || '';
          if (!out.system && res.sdkVersion) out.system = 'webOS ' + res.sdkVersion;
        }
        finish();
      });
      wait();
      luna('luna://com.webos.service.systemservice/osInfo/query', {parameters: ['webos_release']}, function (res) {
        if (res && res.webos_release) out.system = 'webOS ' + res.webos_release;
        finish();
      });
      wait();
      luna('luna://com.palm.connectionmanager/getStatus', {}, function (res) {
        if (res) {
          var wired = res.wired || {}, wifi = res.wifi || {};
          if (wired.state === 'connected' && wired.ipAddress) out.ip = wired.ipAddress;
          else if (wifi.state === 'connected' && wifi.ipAddress) out.ip = wifi.ipAddress;
        }
        finish();
      });
      wait();
      // The app's own version: its appinfo.json, beside index.html.
      var x = new XMLHttpRequest();
      x.onreadystatechange = function () {
        if (x.readyState !== 4) return;
        try { out.version = JSON.parse(x.responseText).version || ''; } catch (e) {}
        finish();
      };
      try { x.open('GET', 'appinfo.json', true); x.send(); } catch (e) { finish(); }
    } else if (samsung) {
      var info = window.tizen.systeminfo;
      try { out.model = info.getCapability('http://tizen.org/system/model_name') || ''; } catch (e) {}
      try { out.system = 'Tizen ' + info.getCapability('http://tizen.org/feature/platform.version'); } catch (e) {}
      try { out.version = window.tizen.application.getCurrentApplication().appInfo.version || ''; } catch (e) {}
      var property = function (name, got) {
        wait();
        try {
          info.getPropertyValue(name, function (v) { got(v); finish(); }, function () { finish(); });
        } catch (e) {
          finish();
        }
      };
      // BUILD's buildVersion is the firmware (T-KTMDEUC-1250.4); its model is the panel's (UN55NU7100) where model_name is a code.
      property('BUILD', function (v) {
        if (v.buildVersion) out.firmware = v.buildVersion;
        if (v.model) out.model = v.model;
      });
      property('ETHERNET_NETWORK', function (v) {
        if (v.status === 'ON' && v.ipAddress) out.ip = v.ipAddress;
      });
      property('WIFI_NETWORK', function (v) {
        if (v.status === 'ON' && v.ipAddress && !out.ip) out.ip = v.ipAddress;
      });
    } else {
      out = {model: 'OLED55C1', firmware: '03.33.85', system: 'webOS 6.0', version: 'preview', ip: '192.168.1.50'};
    }
    finish();
  }

  // MARK: - The TV, whichever it is

  return {
    name: webos ? 'webos' : samsung ? 'tizen' : 'browser',

    /**
     * Whether an app can open the TV's settings. Not on Tizen 2 (a 2016 KU6300): the "settings" request and
     * org.tizen.menu both answer success and nothing shows, and the remote API ignores MENU and HOME. Nor on Tizen 3
     * and 4, where org.tizen.menu freezes the TV (#257): the remote's own Settings button still works there.
     */
    canOpenSettings: !tizen2 && !tizen34,

    /** Installed apps, as the TV's launcher lists them: [{id, title, icon, color}]. */
    apps: function (done) {
      if (samsung) { samsungApps(done); return; }
      if (!webos) {
        done(SAMPLE.map(function (s) { return {id: s[0], title: s[1], icon: '', color: s[2]}; }));
        return;
      }
      function list(res) {
        var out = [];
        var points = res && res.launchPoints ? res.launchPoints : [];
        for (var i = 0; i < points.length; i++) {
          var p = points[i];
          if (!p.id || p.id === self || HIDDEN.test(p.id)) continue;
          out.push({id: p.id, title: p.title || p.id, icon: p.largeIcon || p.icon || '', color: p.bgColor || p.iconColor || ''});
        }
        return out;
      }
      luna('luna://com.webos.applicationManager/listLaunchPoints', {}, function (res) {
        if (res) { done(list(res)); return; }
        // Denied to Developer Mode apps (webOS 6 and newer): once the TV has allowed the remote control API, it lists
        // every app with its icon; until then, the known apps the TV says it has, while the TV asks once (an answer
        // only counts from the next load on, and a "no" isn't asked again).
        // webOS 3 has neither getAppLoadStatus nor a remote control API that takes file:// pages ("invalid origin"):
        // there the list comes from the computer (webos/apps.py), built into the LG package.
        var built = (window.CONFIG && CONFIG.installedApps) || [];
        if (built.length) {
          done(built.filter(function (a) { return a.id !== self && !HIDDEN.test(a.id); }));
          return;
        }
        var paired = false, refused = false;
        try { paired = !!localStorage.getItem(SSAP_KEY); refused = !!localStorage.getItem(SSAP_REFUSED); } catch (e) {}
        if (paired) {
          ssap('com.webos.applicationManager/listLaunchPoints', function (res2) {
            if (res2) done(list(res2)); else knownApps(done);
          });
          return;
        }
        knownApps(done);
        if (!refused) ssap('com.webos.applicationManager/listLaunchPoints', function () {});
      });
    },

    /** Opens an app (or an input); done(ok). */
    launch: function (id, params, done) {
      if (samsung) {
        if (id.indexOf('source:') === 0) samsungSource(id, done);
        else samsungLaunch(id, done);
        return;
      }
      if (!webos) {
        if (done) setTimeout(function () { done(true); }, 0);
        return;
      }
      luna('luna://com.webos.applicationManager/launch', params ? {id: id, params: params} : {id: id}, function (res) {
        if (done) done(!!res);
      });
    },

    /** The antenna and HDMI inputs; `connected` when the TV says a device is on (when it tells). */
    inputs: function (done) {
      if (samsung) { samsungInputs(done); return; }
      var list = INPUTS.map(function (x) { return {id: x.id, key: x.key, label: x.label, symbol: x.symbol, connected: true}; });
      if (!webos) {
        list[2].connected = false;
        list[3].connected = false;
        list[4].connected = false;
        done(list);
        return;
      }
      luna('luna://com.webos.service.eim/getAllInputStatus', {}, function (res) {
        var devices = res && res.devices ? res.devices : null;
        if (devices) {
          for (var i = 1; i < list.length; i++) {
            for (var j = 0; j < devices.length; j++) {
              var d = devices[j];
              if (d.appId !== list[i].id) continue;
              list[i].connected = d.connected !== false;
              // A name the person gave the input on the TV ("PS5"), if any.
              if (d.label && !/^hdmi\s*\d$/i.test(d.label)) list[i].label = d.label;
            }
          }
        }
        done(list);
      });
    },

    /** The TV's own settings; done(ok). */
    settings: function (done) {
      if (samsung) samsungSettings(done);
      else this.launch('com.palm.app.settings', null, done);
    },

    /** The TV's model, firmware, system, IP address and this app's version, for About this TV. */
    about: about,

    /** LG: keeps the TV's screensaver away while ours will take over (`hold` says when). */
    holdScreenSaver: holdScreenSaver,

    /** Back on the first screen: back to wherever the person came from. */
    back: function () {
      if (samsung) {
        try { window.tizen.application.getCurrentApplication().hide(); } catch (e) {}
        return;
      }
      if (window.PalmSystem && window.PalmSystem.platformBack) window.PalmSystem.platformBack();
    },

    /** The TV's time zone ("America/Sao_Paulo"), or ''. */
    zone: function () {
      if (samsung) {
        try { return window.tizen.time.getLocalTimezone() || ''; } catch (e) {}
      }
      try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { return ''; }
    },

    /** Key codes: the arrows, OK and the TV's Back (LG 461, Samsung 10009). */
    keys: {left: 37, up: 38, right: 39, down: 40, ok: 13, back: samsung ? 10009 : 461}
  };
})();
