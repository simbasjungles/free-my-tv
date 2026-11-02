/*
 * Free My TV's service on LG webOS: one call to the TV's remote control API (127.0.0.1:3000) for the web app.
 * Newer webOS gives each app's page its own origin (file://io.freemytv-webos), and the API closes any connection
 * that isn't from file:// ("invalid origin"); from here the handshake says file://.
 * Plain ES5 with Node's net only: older webOS ships an old Node.
 */
var net = require('net');
var Service = require('webos-service');

var service = new Service(require('./package.json').name);

function buffer(x) {   // Buffer.from is missing before Node 4.5
  return Buffer.from ? Buffer.from(x) : new Buffer(x);
}

/** A client's text frame: masked, as the protocol asks. */
function frame(text) {
  var data = buffer(text), n = data.length;
  var mask = buffer([Math.random() * 256 | 0, Math.random() * 256 | 0, Math.random() * 256 | 0, Math.random() * 256 | 0]);
  for (var i = 0; i < n; i++) data[i] ^= mask[i % 4];
  return Buffer.concat([buffer(n < 126 ? [0x81, 0x80 | n] : [0x81, 0xfe, n >> 8, n & 0xff]), mask, data]);
}

/** done({clientKey, payload}), {refused: true} when the person said no on the TV, or null. */
function call(uri, clientKey, done) {
  var socket = net.connect(3000, '127.0.0.1'), buf = buffer([]), open = false, text = '', finished = false;
  var timer = setTimeout(function () { finish(null); }, 90000);   // time to answer the TV's prompt
  function finish(result) {
    if (finished) return;
    finished = true;
    clearTimeout(timer);
    socket.destroy();
    done(result);
  }
  function send(obj) {
    socket.write(frame(JSON.stringify(obj)));
  }
  function onMessage(m) {
    if (m.type === 'registered') {
      clientKey = m.payload['client-key'];
      send({type: 'request', id: 'call', uri: 'ssap://' + uri});
    } else if (m.id === 'call') {
      finish(m.type === 'error' ? null : {clientKey: clientKey, payload: m.payload});
    } else if (m.type === 'error') {
      finish(m.id === 'register' ? {refused: true} : null);
    }
  }
  socket.on('connect', function () {
    socket.write('GET / HTTP/1.1\r\nHost: 127.0.0.1:3000\r\nOrigin: file://\r\nUpgrade: websocket\r\n' +
        'Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ZnJlZW15dHYtd2Vib3MtYXBp\r\n\r\n');
  });
  socket.on('data', function (chunk) {
    buf = Buffer.concat([buf, chunk]);
    if (!open) {
      var end = buf.toString('binary').indexOf('\r\n\r\n');
      if (end < 0) return;
      if (!/^HTTP\/1\.1 101/.test(buf.toString('utf8', 0, 12))) { finish(null); return; }
      open = true;
      buf = buf.slice(end + 4);
      var payload = {forcePairing: false, pairingType: 'PROMPT',
        manifest: {manifestVersion: 1, appVersion: '1.0', permissions: ['READ_INSTALLED_APPS', 'READ_RUNNING_APPS']}};
      if (clientKey) payload['client-key'] = clientKey;
      send({type: 'register', id: 'register', payload: payload});
    }
    // The server's frames: unmasked, a message possibly in several (continuation frames until FIN).
    while (buf.length >= 2) {
      var fin = buf[0] & 0x80, op = buf[0] & 0x0f, n = buf[1] & 0x7f, off = 2;
      if (n === 126) {
        if (buf.length < 4) return;
        n = buf.readUInt16BE(2);
        off = 4;
      } else if (n === 127) {
        if (buf.length < 10) return;
        n = buf.readUInt32BE(6);
        off = 10;
      }
      if (buf.length < off + n) return;
      var data = buf.slice(off, off + n);
      buf = buf.slice(off + n);
      if (op === 8) { finish(null); return; }
      if (op !== 1 && op !== 0) continue;
      text += data.toString('utf8');
      if (!fin) continue;
      var m = null;
      try { m = JSON.parse(text); } catch (e) {}
      text = '';
      if (m) onMessage(m);
    }
  });
  socket.on('error', function () { finish(null); });
  socket.on('close', function () { finish(null); });
}

/*
 * Bringing Free My TV back after another app is closed. The page cannot do it: webOS suspends it when
 * another app comes to the front (its DevTools target disappears), and this service, a Node process,
 * keeps running. So the page hands over the client key when it opens an app, and this service watches
 * the app in the foreground until the person leaves it: on the TV's own launcher, it launches Free My
 * TV again, which comes back through webOSRelaunch with its screen as it was.
 *
 * It asks the remote control API rather than subscribing: getForegroundAppInfo answers
 * `subscribed: true` and then never sends an update (tested on webOS 7.6, 1 Nov 2026).
 */
var watch = null, activity = null;

function stopWatch() {
  if (watch) {
    clearInterval(watch.timer);
    watch = null;
  }
  if (activity) {
    try { activity.releaseKeepAlive ? activity.releaseKeepAlive() : activity.release(); } catch (e) {}
    activity = null;
  }
}

function launchSelf(id) {
  try {
    service.call('luna://com.webos.applicationManager/launch', {id: id}, function () {});
    return true;
  } catch (e) {
    return false;
  }
}

function watchTick() {
  if (!watch) return;
  var w = watch;
  if (Date.now() > w.until) { stopWatch(); return; }   // nobody came back: stop asking
  call('com.webos.applicationManager/getForegroundAppInfo', w.key, function (result) {
    if (!watch || watch !== w) return;
    var payload = result && result.payload ? result.payload : null;
    var appId = payload ? payload.appId : null;
    if (!appId) return;
    if (appId === w.self) {
      // Back in front: the launch worked, or the app we opened never came up (15 s of grace).
      if (w.sawTarget || Date.now() - w.armedAt > 15000) stopWatch();
      return;
    }
    if (appId === w.target) { w.sawTarget = true; return; }
    if (appId === 'com.webos.app.home') {
      // The person left the app. Two tries in a minute: after that, the TV's home is what they want.
      w.strikes = Date.now() - w.last < 60000 ? w.strikes + 1 : 1;
      w.last = Date.now();
      if (w.strikes > 2) { stopWatch(); return; }
      if (launchSelf(w.self)) {
        w.sawTarget = true;
        w.until = Date.now() + 30000;   // enough for the relaunch to land
      }
      return;
    }
    stopWatch();   // another app: the person moved on
  });
}

service.register('returnWatch', function (message) {
  var p = message.payload || {};
  stopWatch();
  if (!p.on || !p.clientKey) {
    message.respond({returnValue: true, watching: false});
    return;
  }
  var self = p.self || String(require('./package.json').name).replace(/\.service$/, '');
  watch = {key: p.clientKey, target: p.appId, self: self, strikes: 0, last: 0, sawTarget: false,
           armedAt: Date.now(), until: Date.now() + (p.minutes || 15) * 60000};
  // Without this the TV collects the idle service, and the process goes with it.
  try {
    activity = service.activityManager.create('idle');
    activity.keepAlive();
  } catch (e) {}
  watch.timer = setInterval(watchTick, 2000);
  // The service may have just booted, and the first read is what tells us whether the launch even happened.
  setTimeout(watchTick, 100);
  message.respond({returnValue: true, watching: true});
});

service.register('ssap', function (message) {
  var p = message.payload || {};
  // Only the app list, the one call the page makes: nothing else goes to the TV's API, nor gets its client key.
  if (p.uri !== 'com.webos.applicationManager/listLaunchPoints') {
    message.respond({returnValue: false, errorText: 'Only listLaunchPoints'});
    return;
  }
  call(p.uri, p.clientKey, function (result) {
    if (!result) message.respond({returnValue: false, errorText: 'No answer from the remote control API'});
    else {
      result.returnValue = true;
      message.respond(result);
    }
  });
});
