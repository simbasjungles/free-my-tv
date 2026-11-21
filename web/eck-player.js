/* On-demand official YouTube IFrame API. Accepted/ready is never reported as playback. ES5. */
var ECKPlayer = (function () {
  'use strict';
  var overlay = null, player = null, serial = 0, timer = 0, check = 0, script = null, bridgeListener = null, bridgeFrame = null;
  var bridgeURL = 'https://celilkamis-arch.github.io/ECK-TV/player.html';
  var info = {phase: 'closed', video: '', confirmed: false, error: 0}, closed = null, failed = null;
  function close() {
    serial++; clearTimeout(timer); clearInterval(check); check = 0;
    if (bridgeListener) window.removeEventListener('message', bridgeListener); bridgeListener = null; bridgeFrame = null;
    if (player) { try { player.destroy(); } catch (e) {} } player = null;
    if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay); overlay = null;
    info.phase = 'closed'; var cb = closed; closed = null; failed = null; if (cb) cb();
  }
  function open(parent, video, onClose, onError) {
    if (!/^[A-Za-z0-9_-]{11}$/.test(video || '')) { onError('Bu film için oynatılabilir fragman bulunamadı.'); return; }
    if (overlay) close();
    var mine = ++serial, previousTime = -1;
    info = {phase: 'loading', video: video, confirmed: false, error: 0}; closed = onClose; failed = onError;
    overlay = document.createElement('section'); overlay.className = 'eck-player'; overlay.setAttribute('aria-label', 'Film fragmanı');
    var slot = document.createElement('div'); slot.id = 'eck-youtube-player'; overlay.appendChild(slot); parent.appendChild(overlay);
    function fail(code) {
      if (mine !== serial) return;
      var cb = failed; info.error = code || 0; info.confirmed = false; close(); info.phase = 'error';
      if (cb) cb('Fragman bu TV’de oynatılamadı.' + (code ? ' (' + code + ')' : ''));
    }
    function start() {
      if (mine !== serial || !window.YT || !window.YT.Player) return;
      var vars = {autoplay: 1, playsinline: 1, controls: 1, rel: 0, disablekb: 1,
        widget_referrer: 'https://github.com/celilkamis-arch/ECK-TV'};
      // A packaged file/widget has no HTTP origin. Never invent one or spoof a Referer.
      if (/^https?:$/.test(location.protocol)) vars.origin = location.protocol + '//' + location.host;
      try {
        player = new window.YT.Player(slot.id, {width: '1920', height: '1080', videoId: video, playerVars: vars, events: {
          onReady: function (e) {
            if (mine !== serial) return;
            info.phase = 'ready';
            try { var frame = e.target.getIframe(); frame.setAttribute('tabindex', '-1'); frame.setAttribute('allow', 'autoplay; encrypted-media; fullscreen'); frame.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin'); parent.setAttribute('tabindex', '-1'); parent.focus(); e.target.playVideo(); } catch (ignore) {}
          },
          onStateChange: function (e) { if (mine === serial && e.data === 0) close(); },
          onError: function (e) { fail(e.data); },
          onAutoplayBlocked: function () { fail('autoplay'); }
        }});
        check = setInterval(function () {
          if (mine !== serial || !player) return;
          try {
            var data = player.getVideoData(), time = player.getCurrentTime();
            if (player.getPlayerState() === 1 && data.video_id === video && previousTime >= 0 && time > previousTime + 0.15) {
              info.phase = 'playing'; info.confirmed = true; clearTimeout(timer); clearInterval(check); check = 0;
            }
            previousTime = time;
          } catch (ignore) {}
        }, 1000);
      } catch (e) { fail(0); }
    }
    timer = setTimeout(function () { fail('timeout'); }, 20000);
    if (!/^https?:$/.test(location.protocol)) {
      // The packaged widget cannot supply HTTP Referer; an owned HTTPS document supplies its real origin.
      var frame = document.createElement('iframe'), origin = /^https:\/\/[^/]+/.exec(bridgeURL)[0];
      bridgeFrame = frame; frame.src = bridgeURL + '#' + video; frame.setAttribute('tabindex', '-1'); frame.setAttribute('allow', 'autoplay; encrypted-media; fullscreen');
      bridgeListener = function (e) {
        if (mine !== serial || e.source !== frame.contentWindow || e.origin !== origin || !e.data || e.data.type !== 'eck-player' || e.data.video !== video) return;
        if (e.data.phase === 'error') { fail(e.data.error); return; }
        if (e.data.phase === 'close' || e.data.phase === 'ended') { close(); return; }
        if (e.data.phase === 'playing' && e.data.confirmed === true) { info.phase = 'playing'; info.confirmed = true; clearTimeout(timer); }
      };
      window.addEventListener('message', bridgeListener); frame.onerror = function () { fail('network'); }; overlay.replaceChild(frame, slot);
      parent.setAttribute('tabindex', '-1'); parent.focus(); return;
    }
    if (window.YT && window.YT.Player) start();
    else {
      window.onYouTubeIframeAPIReady = start;
      if (!script) { script = document.createElement('script'); script.src = 'https://www.youtube.com/iframe_api'; script.onerror = function () { script = null; if (window.onYouTubeIframeAPIReady === start) fail('network'); }; document.head.appendChild(script); }
    }
  }
  return {open: open, close: close, active: function () { return !!overlay; },
    play: function () { try { if (player) player.playVideo(); else if (bridgeFrame) bridgeFrame.contentWindow.postMessage({type: 'eck-player-command', command: 'play'}, 'https://celilkamis-arch.github.io'); } catch (e) {} },
    state: function () { return {phase: info.phase, video: info.video, confirmed: info.confirmed, error: info.error}; }};
})();
