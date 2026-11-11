/*
 * The first top button's setting (Home Settings > Live TV button, web/ on LG and Samsung): the choice kept on the TV
 * is checked, and anything unexpected falls back to Live TV. Run by tools/test-web.sh.
 */
'use strict';
var fs = require('fs'), path = require('path'), vm = require('vm');

function prefs(stored, config) {
  var sandbox = {
    window: {CONFIG: config || {}},
    navigator: {language: 'en-US'},
    localStorage: {
      getItem: function () { return stored === undefined ? null : JSON.stringify(stored); },
      setItem: function () {}
    },
    I18N: {},
    TV: {zone: function () { return ''; }},
    XMLHttpRequest: function () {}
  };
  var code = fs.readFileSync(path.join(__dirname, '..', 'web', 'data.js'), 'utf8');
  vm.runInNewContext(code, sandbox, {filename: 'web/data.js'});
  return sandbox.Prefs;
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

if (failures) process.exit(1);
console.log('WebPrefsTest: all checks passed.');
