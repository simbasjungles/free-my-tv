/*
 * The first top button's setting (Home Settings > Live TV button, web/ on LG and Samsung): the choice kept on the TV
 * is checked, and anything unexpected falls back to Live TV. Run by tools/test-web.sh.
 */
'use strict';
var fs = require('fs'), path = require('path'), vm = require('vm');
var dataCode = fs.readFileSync(path.join(__dirname, '..', 'web', 'data.js'), 'utf8');

function loadData(store, config, respond) {
  var mem = store || {};
  var sandbox = {
    window: {CONFIG: config || {}},
    navigator: {language: 'en-US'},
    localStorage: {
      getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; },
      setItem: function (k, v) { mem[k] = String(v); }
    },
    I18N: {},
    TV: {zone: function () { return ''; }},
    XMLHttpRequest: function () {
      var x = {open: function (m, u) { this.url = u; }, setRequestHeader: function () {}};
      x.send = function () {
        var r = respond ? respond(x.url) : {status: 500, body: {}};
        x.readyState = 4;
        x.status = r.status;
        x.responseText = JSON.stringify(r.body);
        if (x.onreadystatechange) x.onreadystatechange();
      };
      return x;
    }
  };
  vm.runInNewContext(dataCode, sandbox, {filename: 'web/data.js'});
  return sandbox;
}

function prefs(stored, config) {
  var mem = stored === undefined ? {} : {'fmtv.prefs': JSON.stringify(stored)};
  return loadData(mem, config).Prefs;
}

var failures = 0;
function expect(actual, want, label) {
  if (actual === want) return;
  failures++;
  console.error('FAIL ' + label + ': got ' + JSON.stringify(actual) + ', want ' + JSON.stringify(want));
}

expect(prefs({}).boxInput(), 'live', 'nothing picked yet');
expect(prefs({boxInput: 'live'}).boxInput(), 'live', 'Live TV');
expect(prefs({boxInput: 'hdmi2'}).boxInput(), 'hdmi2', 'an HDMI port');
expect(prefs({boxInput: 'HDMI3'}).boxInput(), 'hdmi3', 'case does not matter');
expect(prefs({boxInput: 'hdmi0'}).boxInput(), 'live', 'no port 0');
expect(prefs({boxInput: 'hdmi5'}).boxInput(), 'live', 'no port 5');
expect(prefs({boxInput: 'apple'}).boxInput(), 'live', 'not an input');
expect(prefs({boxInput: 2}).boxInput(), 'live', 'not a string');

expect(prefs({}).liveInput(), true, 'Live TV in the inputs until hidden');
expect(prefs({liveInput: false}).liveInput(), false, 'Live TV hidden');
expect(prefs({liveInput: 'off'}).liveInput(), true, 'not a boolean');

expect(prefs({}).inputsOrder(), null, 'the TV inputs order until the editor is used');
expect(prefs({inputsOrder: ['hdmi2', 'live']}).inputsOrder().join(','), 'hdmi2,live', 'the saved inputs order');
expect(prefs({inputsOrder: 'hdmi2'}).inputsOrder(), null, 'an order that is not a list');
expect(prefs({}).inputsHidden().join(','), '', 'no input hidden until picked');
expect(prefs({liveInput: false}).inputsHidden().join(','), 'live', 'the Live TV switch until the editor is used');
expect(prefs({liveInput: false, inputsHidden: []}).inputsHidden().join(','), '', 'the editor carries the switch over');
expect(prefs({inputsHidden: ['hdmi3']}).inputsHidden().join(','), 'hdmi3', 'a hidden input');
expect(prefs({inputsHidden: 'none'}).inputsHidden().join(','), '', 'hidden inputs that are not a list');

expect(prefs({}).clockFormat(), 'auto', 'the clock of the language until picked');
expect(prefs({}, {clockFormat: '24'}).clockFormat(), '24', 'clock_format from the build');
expect(prefs({clockFormat: '12'}, {clockFormat: '24'}).clockFormat(), '12', 'the choice on the TV wins');
expect(prefs({clockFormat: 'auto'}, {clockFormat: '24'}).clockFormat(), 'auto', 'Automatic picked on the TV');
expect(prefs({clockFormat: 'noon'}).clockFormat(), 'auto', 'not a clock');

// hide_services is where the Picks from switches start; a switch changed on the TV wins from then on.
var hide = {hideServices: ['netflix', 'appletv']};
expect(prefs({}).serviceOff('netflix'), false, 'every service on without hide_services');
expect(prefs({}, hide).serviceOff('netflix'), true, 'hide_services until switched on the TV');
expect(prefs({}, hide).serviceOff('prime'), false, 'a service hide_services leaves alone');
expect(prefs({servicesOff: []}, hide).serviceOff('netflix'), false, 'all switched on on the TV wins');
expect(prefs({servicesOff: ['prime']}, hide).serviceOff('appletv'), false, 'the list kept on the TV wins');
expect(prefs({servicesOff: ['prime']}, hide).serviceOff('prime'), true, 'switched off on the TV');
var switched = prefs({}, hide);
switched.setServiceOff('netflix', false);
expect(switched.serviceOff('netflix'), false, 'switched on on the TV');
expect(switched.serviceOff('appletv'), true, 'the rest of hide_services stays off');
expect(hide.hideServices.length, 2, 'the build\'s list is not changed');
// Samsung's Home button row: on in a build made with samsung_home_button, until turned off on the TV.
expect(prefs({}).homeReturns(), false, 'Home stays with the TV');
expect(prefs({}, {homeButton: true}).homeReturns(), true, 'samsung_home_button from the build');
expect(prefs({homeReturns: false}, {homeButton: true}).homeReturns(), false, 'turned off on the TV');
expect(prefs({homeReturns: 'yes'}).homeReturns(), false, 'not a boolean');

// Dollar toggle & live Data.currency across FX conversion and dollar fallback.
var br = loadData({}, {currency: 'BRL'});
expect(br.Data.currency, 'BRL', 'BRL starts in BRL');
expect(br.Data.dollarRow(), true, 'BRL exposes Dollar row');
expect(br.Data.activeCoins().join(','), '0,1,2,3', 'BRL activates Dollar card');

var usd = loadData({}, {currency: 'USD'});
expect(usd.Data.currency, 'USD', 'USD starts in USD');
expect(usd.Data.dollarRow(), false, 'USD has no Dollar row');
expect(usd.Data.activeCoins().join(','), '0,1,2', 'USD omits Dollar card');

var fxCached = loadData({'fmtv.fxFor': JSON.stringify('COP')}, {currency: 'COP'});
expect(fxCached.Data.currency, 'COP', 'cached FX keeps COP');
expect(fxCached.Data.dollarRow(), true, 'cached FX exposes Dollar row');
expect(fxCached.Data.activeCoins().join(','), '0,1,2,3', 'cached FX activates Dollar card');
fxCached.Prefs.setCoin(3, false);
expect(fxCached.Data.dollarRow(), true, 'hiding Dollar card keeps Dollar toggle');
expect(fxCached.Data.activeCoins().join(','), '0,1,2', 'toggling Dollar off drops Dollar card');

var fxLive = loadData({}, {currency: 'COP'}, function (url) {
  if (url.indexOf('USDTCOP') >= 0) {
    return {status: 200, body: [
      {symbol: 'BTCUSDT', lastPrice: '90000', openPrice: '89000'},
      {symbol: 'ETHUSDT', lastPrice: '3000', openPrice: '2900'},
      {symbol: 'SOLUSDT', lastPrice: '200', openPrice: '190'},
      {symbol: 'USDTCOP', lastPrice: '4000', openPrice: '3950'}
    ]};
  }
  return {status: 400, body: {msg: 'Invalid symbol.'}};
});
expect(fxLive.Data.dollarRow(), false, 'COP has no Dollar row before FX discovery');
fxLive.Data.settingsChanged();
expect(fxLive.Data.currency, 'COP', 'FX discovery keeps COP');
expect(fxLive.Data.dollarRow(), true, 'FX discovery exposes Dollar row');
expect(fxLive.Data.activeCoins().join(','), '0,1,2,3', 'FX discovery activates Dollar card');

var inrFallback = loadData({}, {currency: 'INR'}, function (url) {
  if (url.indexOf('INR') < 0) {
    return {status: 200, body: [
      {symbol: 'BTCUSDT', lastPrice: '90000', openPrice: '89000'},
      {symbol: 'ETHUSDT', lastPrice: '3000', openPrice: '2900'},
      {symbol: 'SOLUSDT', lastPrice: '200', openPrice: '190'}
    ]};
  }
  return {status: 400, body: {msg: 'Invalid symbol.'}};
});
inrFallback.Data.settingsChanged();
expect(inrFallback.Data.currency, 'USD', '400 on direct and FX pairs moves Data.currency to USD');
expect(inrFallback.Data.dollarRow(), false, 'dollar fallback drops Dollar row');
expect(inrFallback.Data.activeCoins().join(','), '0,1,2', 'dollar fallback omits Dollar card');

var haltedBr = loadData({}, {currency: 'BRL'}, function (url) {
  if (url.indexOf('BRL') >= 0) {
    return {status: 200, body: [
      {symbol: 'BTCBRL', lastPrice: '500000', openPrice: '500000', closeTime: 1}
    ]};
  }
  return {status: 200, body: [
    {symbol: 'BTCUSDT', lastPrice: '90000', openPrice: '89000', closeTime: Date.now()},
    {symbol: 'ETHUSDT', lastPrice: '3000', openPrice: '2900', closeTime: Date.now()},
    {symbol: 'SOLUSDT', lastPrice: '200', openPrice: '190', closeTime: Date.now()}
  ]};
});
haltedBr.Data.settingsChanged();
expect(haltedBr.Data.currency, 'USD', 'halted market moves Data.currency to USD');
expect(haltedBr.Data.dollarRow(), false, 'halted BRL market drops Dollar row');
expect(haltedBr.Data.activeCoins().join(','), '0,1,2', 'halted BRL market omits Dollar card');

if (failures) process.exit(1);
console.log('WebPrefsTest: all checks passed.');
